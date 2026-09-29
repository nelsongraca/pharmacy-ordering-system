package com.flowkode.pharma.service

import com.flowkode.pharma.domain.*
import com.flowkode.pharma.dto.OrderPayload
import com.flowkode.pharma.util.transactional
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

@ApplicationScoped
class OrderService(
    private val prescriptionService: PrescriptionService,
    private val stockService: StockService,
    private val statusPublisher: StatusPublisher,
) {

    private val log = Logger.getLogger(OrderService::class.java)

    @Channel("packaging")
    lateinit var packaging: Emitter<OrderPayload>

    fun create(prescriptionNumber: String): OrderResult {
        val code = prescriptionNumber.trim().uppercase()

        val items = try {
            prescriptionService.fetchPrescription(code)
        }
        catch (ex: PrescriptionUnavailableException) {
            log.error("Prescription source unavailable", ex)
            return OrderResult.Unavailable
        }

        // best-effort guard against double submits; not race-proof (no DB partial index, see DESIGN.md)
        if (hasActiveOrder(code)) return OrderResult.AlreadyActive

        val ticket = try {
            transactional { reserveAndCreate(code, items) }
        }
        catch (ex: NoStockException) {
            //record the failure in its own transaction
            //todo: not happy with this approach
            val orderId = transactional { createOutOfStock(code) }
            statusPublisher.publish(orderId, PrescriptionStatus.OUT_OF_STOCK)
            return OrderResult.OutOfStock(ex.medicationId)
        }

        statusPublisher.publish(ticket, PrescriptionStatus.AWAITING_APPROVAL)
        return OrderResult.Placed(ticket)
    }

    private fun hasActiveOrder(code: String): Boolean =
        transactional {
            Order.count(
                "prescriptionCode = ?1 and status in ?2",
                code,
                listOf(
                    PrescriptionStatus.AWAITING_APPROVAL,
                    PrescriptionStatus.IN_REVIEW,
                    PrescriptionStatus.PACKAGING,
                    PrescriptionStatus.READY,
                ),
            ) > 0
        }

    // get next from db, based on the role the pharmacist works
    fun claimNext(role: PharmacistRole, by: String): Long? =
        transactional {
            when (role) {
                PharmacistRole.APPROVALS  -> Order.claimOldest(by, PrescriptionStatus.IN_REVIEW, PrescriptionStatus.AWAITING_APPROVAL)
                PharmacistRole.DELIVERIES -> Order.claimOldest(by, null, PrescriptionStatus.READY)
                PharmacistRole.BOTH       -> Order.claimOldest(by, null, PrescriptionStatus.READY)
                    ?: Order.claimOldest(by, PrescriptionStatus.IN_REVIEW, PrescriptionStatus.AWAITING_APPROVAL)
            }
        }

    /** The order this pharmacist is holding, if any. They take one at a time. */
    fun heldBy(by: String): Long? =
        transactional { Order.heldBy(by)?.id }

    fun countWaiting(role: PharmacistRole): Long =
        transactional {
            when (role) {
                PharmacistRole.APPROVALS  -> Order.countByStatus(PrescriptionStatus.AWAITING_APPROVAL)
                PharmacistRole.DELIVERIES -> Order.countByStatus(PrescriptionStatus.READY)
                PharmacistRole.BOTH       -> Order.countByStatus(PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.READY)
            }
        }

    fun approve(id: Long): Boolean {
        val approved = transactional { Order.doTransition(id, PrescriptionStatus.IN_REVIEW, PrescriptionStatus.PACKAGING) }
        if (!approved) return false

        statusPublisher.publish(id, PrescriptionStatus.PACKAGING)
        publishPackaging(id)
        return true
    }

    /** Send the packaging work message and record it, so the republisher can recover if it is lost. */
    fun publishPackaging(id: Long) {
        try {
            packaging.send(OrderPayload(id))
            transactional { Order.markPublished(id) }
        }
        catch (ex: Exception) {
            log.errorf(ex, "Failed to publish packaging for order %d", id)
        }
    }

    fun reject(id: Long): Boolean {
        val rejected = transactional {
            if (!Order.rejectFromReview(id)) return@transactional false

            stockService.release(Order.findById(id)!!)
            true
        }

        if (rejected) statusPublisher.publish(id, PrescriptionStatus.REJECTED)
        return rejected
    }

    fun handover(id: Long, by: String): Boolean {
        val completed = transactional {
            if (!Order.completeFromReady(id, by)) return@transactional false

            stockService.consume(Order.findById(id)!!)
            true
        }

        if (completed) statusPublisher.publish(id, PrescriptionStatus.COMPLETED)
        return completed
    }

    /** READY stays READY; we only record that the ticket number was called out (board highlights it briefly). */
    fun call(id: Long, by: String): Boolean {
        val called = transactional { Order.markCalled(id, by) }
        if (called) statusPublisher.publish(id, PrescriptionStatus.READY)
        return called
    }

    fun dismiss(id: Long): Boolean {
        val status = transactional {
            if (!Order.dismiss(id)) return@transactional null
            Order.findById(id)?.status
        } ?: return false

        statusPublisher.publish(id, status)
        return true
    }

    private fun reserveAndCreate(code: String, items: Map<Long, Long>): Long {

        for ((medicationId, quantity) in items.entries.sortedBy { it.key }) {
            // false means no stock or missing
            if (!Medication.reserve(medicationId, quantity)) throw NoStockException(medicationId)
        }

        val order = Order().apply {
            prescriptionCode = code
            status = PrescriptionStatus.AWAITING_APPROVAL
        }

        for ((medicationId, quantity) in items) {
            order.items.add(
                OrderItem().apply {
                    this.order = order
                    this.medication = Medication.findById(medicationId)!!
                    amount = quantity
                },
            )
        }

        order.persistAndFlush()
        return order.id!!
    }

    private fun createOutOfStock(code: String): Long {
        val order = Order().apply {
            prescriptionCode = code
            status = PrescriptionStatus.OUT_OF_STOCK
        }
        order.persistAndFlush()
        return order.id!!
    }
}

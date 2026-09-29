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
) {

    private val log = Logger.getLogger(OrderService::class.java)

    @Channel("packaging")
    lateinit var packaging: Emitter<OrderPayload>

    fun create(prescriptionNumber: String): OrderResult {
        val items = prescriptionService.fetchPrescription(prescriptionNumber)

        val ticket = try {
            transactional { reserveAndCreate(prescriptionNumber, items) }
        }
        catch (ex: NoStockException) {
            //record the failure in its own transaction
            //todo: not happy with this approach
            transactional { createOutOfStock(prescriptionNumber) }
            return OrderResult.OutOfStock(ex.medicationId)
        }

        return OrderResult.Placed(ticket)
    }

    // get next from db, based on the role the pharmacist works
    fun claimNext(role: PharmacistRole): Long? =
        transactional {
            when (role) {
                PharmacistRole.APPROVALS  -> Order.claimOldest(PrescriptionStatus.IN_REVIEW, PrescriptionStatus.AWAITING_APPROVAL)
                PharmacistRole.DELIVERIES -> Order.claimOldest(null, PrescriptionStatus.READY) //todo: claim is not persisted; consider claimed_by/claimed_at (see notes.md)
                PharmacistRole.BOTH       -> Order.claimOldest(null, PrescriptionStatus.READY) //todo: same, see above
                    ?: Order.claimOldest(PrescriptionStatus.IN_REVIEW, PrescriptionStatus.AWAITING_APPROVAL)
            }
        }

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

        try {
            packaging.send(OrderPayload(id))
        }
        catch (ex: Exception) {
            log.errorf(ex, "Failed to publish packaging for order %d", id)
        }
        return true
    }

    fun reject(id: Long): Boolean =
        transactional {
            if (!Order.rejectFromReview(id)) return@transactional false

            stockService.release(Order.findById(id)!!)
            true
        }

    fun handover(id: Long): Boolean =
        transactional {
            if (!Order.doTransition(id, PrescriptionStatus.READY, PrescriptionStatus.COMPLETED)) return@transactional false

            stockService.consume(Order.findById(id)!!)
            true
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

    private fun createOutOfStock(code: String) {
        Order().apply {
            prescriptionCode = code
            status = PrescriptionStatus.OUT_OF_STOCK
        }
            .persist()
    }
}

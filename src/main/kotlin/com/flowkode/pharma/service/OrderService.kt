package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.domain.PrescriptionStatus
import io.quarkus.narayana.jta.QuarkusTransaction
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
    lateinit var packaging: Emitter<String>

    fun create(prescriptionNumber: String): OrderResult {
        val items = prescriptionService.fetchPrescription(prescriptionNumber)

        val ticket = try {
            QuarkusTransaction.requiringNew().call { reserveAndCreate(prescriptionNumber, items) }
        } catch (ex: NoStockException) {
            //record the failure in its own transaction
            //todo: not happy with this approach
            QuarkusTransaction.requiringNew().run { createOutOfStock(prescriptionNumber) }
            return OrderResult.OutOfStock(ex.medicationId)
        }

        return OrderResult.Placed(ticket)
    }

    // get next from db, based on the role the pharmacist works
    fun claimNext(role: PharmacistRole): Long? =
        QuarkusTransaction.requiringNew().call {
            when (role) {
                PharmacistRole.APPROVALS, PharmacistRole.BOTH -> Order.claimOldestAwaiting()
                PharmacistRole.DELIVERIES -> null // TODO: READY orders once deliveries exist
            }
        }

    fun countWaiting(role: PharmacistRole): Long =
        QuarkusTransaction.requiringNew().call {
            when (role) {
                PharmacistRole.APPROVALS, PharmacistRole.BOTH -> Order.countAwaitingApproval()
                PharmacistRole.DELIVERIES -> 0L
            }
        }

    fun approve(id: Long): Boolean {
        val approved = QuarkusTransaction.requiringNew().call { Order.approveFromReview(id) }
        if (!approved) return false

        try {
            packaging.send("""{"orderId":$id}""")
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to publish packaging for order %d", id)
        }
        return true
    }

    fun reject(id: Long): Boolean =
        QuarkusTransaction.requiringNew().call {
            if (!Order.rejectFromReview(id)) return@call false

            stockService.release(Order.findById(id)!!)
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
        }.persist()
    }
}

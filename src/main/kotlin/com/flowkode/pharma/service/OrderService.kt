package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PrescriptionStatus
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

@ApplicationScoped
class OrderService(
    private val prescriptionService: PrescriptionService,
) {
    private val log = Logger.getLogger(OrderService::class.java)

    @Channel("approval")
    lateinit var approval: Emitter<String>


    fun order(prescriptionNumber: String): OrderResult {
        val items = prescriptionService.fetchPrescription(prescriptionNumber)

        val ticket = try {
            QuarkusTransaction.requiringNew().call { reserveAndCreate(prescriptionNumber, items) }
        } catch (ex: NoStockException) {
            //record the failure in its own transaction
            QuarkusTransaction.requiringNew().run { createOutOfStock(prescriptionNumber) }
            return OrderResult.OutOfStock(ex.medicationId)
        }

        try {
            //publish for approval
            approval.send("""{"orderId":$ticket}""")
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to publish approval for order %d", ticket)
        }

        return OrderResult.Placed(ticket)
    }

    private fun reserveAndCreate(code: String, items: Map<Long, Long>): Long {

        for ((medicationId, quantity) in items.entries.sortedBy { it.key }) {
            val updated = Medication.update(
                "reserved = reserved + ?1 where id = ?2 and stock - reserved >= ?1",
                quantity,
                medicationId,
            )
            // zero rows means no stock or missing
            if (updated == 0) throw NoStockException(medicationId)
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

package com.flowkode.pharma.service

import com.flowkode.pharma.board.BoardTicket
import com.flowkode.pharma.domain.*
import com.flowkode.pharma.util.transactional
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class OrderService(
    private val prescriptionService: PrescriptionService,
    private val stockService: StockService,
    private val statusPublisher: StatusPublisher,
    private val packagingPublisher: PackagingPublisher,
) {

    private val log = Logger.getLogger(OrderService::class.java)

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

        val placed = try {
            transactional { reserveAndCreate(code, items) }
        }
        catch (ex: NoStockException) {
            //record the failure in its own transaction
            //todo: not happy with this approach
            val order = transactional { createOutOfStock(code) }
            statusPublisher.publish(BoardTicket.forOrder(order))
            return OrderResult.OutOfStock(ex.medicationId)
        }

        statusPublisher.publish(BoardTicket.forOrder(placed))
        return OrderResult.Placed(placed.id!!)
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

    /** The order this console is holding, if any. It takes one at a time. */
    fun held(by: String): Long? =
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
        val ok = transition(id, { Order.doTransition(id, PrescriptionStatus.IN_REVIEW, PrescriptionStatus.PACKAGING) })
        if (ok) packagingPublisher.publish(id)
        return ok
    }

    fun reject(id: Long): Boolean =
        transition(id, { Order.rejectFromReview(id) }) { stockService.release(it) }

    fun handover(id: Long, by: String): Boolean =
        transition(id, { Order.completeFromReady(id, by) }) { stockService.consume(it) }

    /** READY stays READY; we only record that the ticket number was called out (board highlights it). */
    fun call(id: Long, by: String): Boolean =
        transition(id, { Order.markCalled(id, by) })

    fun dismiss(id: Long): Boolean =
        transition(id, { Order.dismiss(id) })

    /**
     * Runs a conditional transition; on success builds the ticket from the committed row and
     * publishes it. [effect] applies any stock change in the same transaction. Returns false when
     * the transition lost a race or was repeated (both are no-ops).
     */
    private fun transition(id: Long, guard: () -> Boolean, effect: (Order) -> Unit = {}): Boolean {
        val ticket = transactional {
            if (!guard()) return@transactional null
            // load after the guard: the guard is a bulk update, so a pre-load would be stale
            val order = Order.findById(id) ?: return@transactional null
            effect(order)
            BoardTicket.forOrder(order)
        } ?: return false

        statusPublisher.publish(ticket)
        return true
    }

    private fun reserveAndCreate(code: String, items: Map<Long, Long>): Order {

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
        return order
    }

    private fun createOutOfStock(code: String): Order {
        val order = Order().apply {
            prescriptionCode = code
            status = PrescriptionStatus.OUT_OF_STOCK
        }
        order.persistAndFlush()
        return order
    }
}

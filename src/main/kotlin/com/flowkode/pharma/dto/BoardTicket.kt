package com.flowkode.pharma.board

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PrescriptionStatus

/**
 * One ticket as the board needs it: which column it belongs in, how to render it, and how to order
 * it. Built from the committed row at write time and pushed over SSE, so no board ever re-reads the
 * database to find out that something changed.
 */
data class BoardTicket(
    val id: Long,
    val stage: String,
    val calling: Boolean,
    /** `preparing`, `ready`, `seePharmacist`, or `none` when the ticket has left the board. */
    val column: String,
    /** Ascending time used to order a column (status change, or the call time once called). */
    val sortKey: Long,
) {
    companion object {
        fun forOrder(order: Order): BoardTicket {
            val column = column(order)
            val called = order.calledAt != null
            return BoardTicket(
                id = order.id!!,
                stage = stage(order.status),
                calling = called,
                column = column,
                sortKey = (order.calledAt ?: order.statusChangedAt).toEpochMilli(),
            )
        }

        /** A ticket that is no longer on the board; the browser removes it. */
        fun gone(id: Long): BoardTicket = BoardTicket(id, "", false, "none", 0)

        private fun column(order: Order): String = when {
            order.dismissedAt != null -> "none"
            order.status.preparing -> "preparing"
            order.status == PrescriptionStatus.READY -> "ready"
            order.status.problem -> "seePharmacist"
            else -> "none"
        }

        private fun stage(status: PrescriptionStatus): String = when (status) {
            PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW -> "Pharmacist review"
            PrescriptionStatus.PACKAGING -> "Packing"
            PrescriptionStatus.READY -> "Ready"
            PrescriptionStatus.OUT_OF_STOCK -> "Out of stock"
            PrescriptionStatus.REJECTED -> "Rejected"
            PrescriptionStatus.FAILED -> "Failed"
            else -> status.name
        }
    }
}

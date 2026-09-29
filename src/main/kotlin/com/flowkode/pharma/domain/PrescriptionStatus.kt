package com.flowkode.pharma.domain

enum class PrescriptionStatus {
    OUT_OF_STOCK,
    AWAITING_APPROVAL,
    IN_REVIEW,
    REJECTED,
    PACKAGING,
    READY,
    COMPLETED,
    FAILED,
    ;

    /** On the board's "Preparing" column. */
    val preparing: Boolean get() = this in PREPARING

    /** A problem the pharmacist has to resolve: the board's "See pharmacist" column. */
    val problem: Boolean get() = this in PROBLEMS

    companion object {
        val PREPARING = setOf(AWAITING_APPROVAL, IN_REVIEW, PACKAGING)
        val PROBLEMS = setOf(OUT_OF_STOCK, REJECTED, FAILED)

        /** Every status the board shows (in flight, ready, or a problem). */
        val ON_BOARD = PREPARING + READY + PROBLEMS
    }
}

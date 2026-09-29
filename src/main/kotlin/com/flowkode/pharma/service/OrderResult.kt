package com.flowkode.pharma.service

sealed interface OrderResult {
    data class Placed(val ticket: Long) : OrderResult
    data class OutOfStock(val medicationId: Long) : OrderResult
    data object AlreadyActive : OrderResult
    data object Unavailable : OrderResult
}

/** Thrown inside the reservation transaction when a line cannot be fully reserved. */
class NoStockException(val medicationId: Long) : RuntimeException()

/** Thrown by the prescription source when it cannot answer (outage). */
class PrescriptionUnavailableException : RuntimeException("Prescription source unavailable")

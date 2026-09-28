package com.flowkode.pharma.service

sealed interface OrderResult {
    data class Placed(val ticket: Long) : OrderResult
    data class OutOfStock(val medicationId: Long) : OrderResult
}

/** Thrown inside the reservation transaction when a line cannot be fully reserved. */
class NoStockException(val medicationId: Long) : RuntimeException()

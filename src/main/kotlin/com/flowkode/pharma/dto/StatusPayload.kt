package com.flowkode.pharma.dto

/** Published to the `orders.status` fanout whenever an order changes status. Small on purpose: the board reloads from the DB. */
data class StatusPayload(val orderId: Long, val status: String)

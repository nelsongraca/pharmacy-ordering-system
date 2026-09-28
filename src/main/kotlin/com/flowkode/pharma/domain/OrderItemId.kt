package com.flowkode.pharma.domain

import jakarta.persistence.Embeddable
import java.io.Serializable

@Embeddable
class OrderItemId : Serializable {

    var orderId: Long? = null

    var medicationId: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrderItemId) return false
        return orderId == other.orderId && medicationId == other.medicationId
    }

    override fun hashCode(): Int = 31 * orderId.hashCode() + medicationId.hashCode()
}

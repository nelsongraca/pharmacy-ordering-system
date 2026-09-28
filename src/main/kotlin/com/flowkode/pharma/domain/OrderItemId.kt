package com.flowkode.pharma.domain

import jakarta.persistence.Column
import java.io.Serializable

class OrderItemId : Serializable {

    @Column(name = "order_id")
    var order: Long? = null

    @Column(name = "medication_id")
    var medication: Long? = null
}

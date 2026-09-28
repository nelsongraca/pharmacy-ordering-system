package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*

@Entity
@Table(name = "OrderItem")
class OrderItem : PanacheEntityBase {

    companion object : PanacheCompanionBase<OrderItem, OrderItemId> {

    }

    @EmbeddedId
    var id: OrderItemId = OrderItemId()

    @ManyToOne(optional = false)
    @MapsId("orderId")
    @JoinColumn(name = "order_id")
    lateinit var order: Order

    @ManyToOne(optional = false)
    @MapsId("medicationId")
    @JoinColumn(name = "medication_id")
    lateinit var medication: Medication

    @Column(name = "amount")
    var amount: Long = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrderItem) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

}

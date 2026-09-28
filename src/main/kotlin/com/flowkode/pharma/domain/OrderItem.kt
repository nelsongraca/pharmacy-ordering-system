package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*

@Entity
@Table(name = "OrderItem")
@IdClass(OrderItemId::class)
class OrderItem : PanacheEntityBase {

    companion object : PanacheCompanion<OrderItem> {

    }

    @Id
    @ManyToOne(optional = false)
    lateinit var order: Order

    @Id
    @ManyToOne
    lateinit var medication: Medication

    @Column(name="amount")
    var amount: Long = 0

}

package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table

@Entity
@Table(name = "orders")
class Order : PanacheEntityBase {

    companion object : PanacheCompanion<Order> {

        /** Locks the oldest AWAITING_APPROVAL order and marks it IN_REVIEW. Null when none wait. */
        fun claimOldestAwaiting(): Long? {
            val order = find("status = ?1 order by id", PrescriptionStatus.AWAITING_APPROVAL)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult()
                ?: return null

            order.status = PrescriptionStatus.IN_REVIEW
            return order.id
        }

        fun approveFromReview(id: Long): Boolean =
            update(
                "status = ?1 where id = ?2 and status = ?3",
                PrescriptionStatus.PACKAGING,
                id,
                PrescriptionStatus.IN_REVIEW,
            ) == 1

        fun rejectFromReview(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true where id = ?2 and status = ?3 and stockReleased = false",
                PrescriptionStatus.REJECTED,
                id,
                PrescriptionStatus.IN_REVIEW,
            ) == 1

        fun countAwaitingApproval(): Long = count("status = ?1", PrescriptionStatus.AWAITING_APPROVAL)
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orders_seq")
    @SequenceGenerator(name = "orders_seq", sequenceName = "orders_seq", allocationSize = 1)
    var id: Long? = null

    lateinit var prescriptionCode: String
    var status = PrescriptionStatus.RECEIVED
    var stockReleased = false

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var items: MutableList<OrderItem> = mutableListOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0


}

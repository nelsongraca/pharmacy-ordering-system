package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*

@Entity
@Table(name = "orders")
class Order : PanacheEntityBase {

    companion object : PanacheCompanion<Order> {

        /**
         * Locks the oldest order in any of [status] (oldest ticket first) and, when [target] is given,
         * moves it there. A null [target] claims without changing status (delivery keeps READY).
         * The write lock serializes concurrent claims so two pharmacists never take the same order.
         */
        fun claimOldest(target: PrescriptionStatus?, vararg status: PrescriptionStatus): Long? {
            //ids are sequential so we can order by id, ideally we use an update date so we process the ones that changed more time ago
            //todo: add date for ordering and audit
            val order = find("status in ?1 order by id", status.toList())
                .withLock(LockModeType.PESSIMISTIC_WRITE) //also not a big fan of these for large scale cann be a problem
                .firstResult()
                ?: return null

            if (target != null) order.status = target
            return order.id
        }


        fun doTransition(id: Long, from: PrescriptionStatus, to: PrescriptionStatus): Boolean {
            return update(
                "status = ?1 where id = ?2 and status = ?3",
                to,
                id,
                from,
            ) == 1
        }


        fun rejectFromReview(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true where id = ?2 and status = ?3 and stockReleased = false",
                PrescriptionStatus.REJECTED,
                id,
                PrescriptionStatus.IN_REVIEW,
            ) == 1

        fun countByStatus(vararg status: PrescriptionStatus): Long {
            return count("status in ?1", status.asList())
        }


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

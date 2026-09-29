package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant

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

            if (target != null) {
                order.status = target
                order.statusChangedAt = Instant.now()
            }
            return order.id
        }


        fun doTransition(id: Long, from: PrescriptionStatus, to: PrescriptionStatus): Boolean {
            return update(
                "status = ?1, statusChangedAt = ?2 where id = ?3 and status = ?4",
                to,
                Instant.now(),
                id,
                from,
            ) == 1
        }


        fun rejectFromReview(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true, statusChangedAt = ?2 where id = ?3 and status = ?4 and stockReleased = false",
                PrescriptionStatus.REJECTED,
                Instant.now(),
                id,
                PrescriptionStatus.IN_REVIEW,
            ) == 1

        /** Packager gave up: PACKAGING -> FAILED, releasing stock at most once (guarded by [stockReleased]). */
        fun failFromPackaging(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true, statusChangedAt = ?2 where id = ?3 and status = ?4 and stockReleased = false",
                PrescriptionStatus.FAILED,
                Instant.now(),
                id,
                PrescriptionStatus.PACKAGING,
            ) == 1

        /** READY -> READY (called): remember that this ticket was called out, at most once. */
        fun markCalled(id: Long): Boolean =
            update(
                "calledAt = ?1 where id = ?2 and status = ?3 and calledAt is null",
                Instant.now(),
                id,
                PrescriptionStatus.READY,
            ) == 1

        /** A problem order -> dismissed: hides it from the board, at most once. */
        fun dismiss(id: Long): Boolean =
            update(
                "dismissedAt = ?1 where id = ?2 and status in ?3 and dismissedAt is null",
                Instant.now(),
                id,
                listOf(
                    PrescriptionStatus.OUT_OF_STOCK,
                    PrescriptionStatus.REJECTED,
                    PrescriptionStatus.FAILED,
                ),
            ) == 1

        fun countByStatus(vararg status: PrescriptionStatus): Long {
            return count("status in ?1", status.asList())
        }

        /** Every order the board shows: in flight, ready, or a problem the pharmacist must resolve. */
        fun boardOrders(): List<Order> =
            list(
                "status in ?1 and dismissedAt is null order by id",
                listOf(
                    PrescriptionStatus.AWAITING_APPROVAL,
                    PrescriptionStatus.IN_REVIEW,
                    PrescriptionStatus.PACKAGING,
                    PrescriptionStatus.READY,
                    PrescriptionStatus.OUT_OF_STOCK,
                    PrescriptionStatus.REJECTED,
                    PrescriptionStatus.FAILED,
                ),
            )

        /** Problem orders the pharmacist still has to resolve (or dismiss). */
        fun attentionOrders(): List<Order> =
            list(
                "status in ?1 and dismissedAt is null order by id",
                listOf(
                    PrescriptionStatus.OUT_OF_STOCK,
                    PrescriptionStatus.REJECTED,
                    PrescriptionStatus.FAILED,
                ),
            )


    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orders_seq")
    @SequenceGenerator(name = "orders_seq", sequenceName = "orders_seq", allocationSize = 1)
    var id: Long? = null

    lateinit var prescriptionCode: String
    var status = PrescriptionStatus.RECEIVED
    var stockReleased = false
    var statusChangedAt: Instant = Instant.now()
    var calledAt: Instant? = null
    var dismissedAt: Instant? = null

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var items: MutableList<OrderItem> = mutableListOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0


}

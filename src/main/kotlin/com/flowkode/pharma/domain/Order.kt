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
         * Locks the oldest order in any of [status] (oldest ticket first) and claims it for [by]:
         * stamps `claimedBy`/`claimedAt`; when [target] is given it also moves the status. A null
         * [target] claims without changing status (delivery keeps READY). `claimedBy is null` means
         * only unclaimed orders are taken, so a second "Take next" (from any console) cannot hand out
         * an order already held. The write lock serializes concurrent claims.
         */
        fun claimOldest(by: String, target: PrescriptionStatus?, vararg status: PrescriptionStatus): Long? {
            //ids are sequential so we can order by id, ideally we use an update date so we process the ones that changed more time ago
            //todo: add date for ordering and audit
            val order = find("status in ?1 and claimedBy is null order by id", status.toList())
                .withLock(LockModeType.PESSIMISTIC_WRITE) //also not a big fan of these for large scale cann be a problem
                .firstResult()
                ?: return null

            order.claimedBy = by
            order.claimedAt = Instant.now()
            if (target != null) {
                order.status = target
                order.statusChangedAt = Instant.now()
            }
            return order.id
        }

        /** The order a specific console is holding, if any (one at a time). */
        fun heldBy(by: String): Order? =
            find(
                "claimedBy = ?1 and status in ?2 order by id",
                by,
                listOf(PrescriptionStatus.IN_REVIEW, PrescriptionStatus.READY),
            ).firstResult()


        fun doTransition(id: Long, from: PrescriptionStatus, to: PrescriptionStatus): Boolean {
            return update(
                "status = ?1, statusChangedAt = ?2, claimedBy = null, claimedAt = null where id = ?3 and status = ?4",
                to,
                Instant.now(),
                id,
                from,
            ) == 1
        }


        fun rejectFromReview(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true, statusChangedAt = ?2, claimedBy = null, claimedAt = null where id = ?3 and status = ?4 and stockReleased = false",
                PrescriptionStatus.REJECTED,
                Instant.now(),
                id,
                PrescriptionStatus.IN_REVIEW,
            ) == 1

        /** Packager gave up: PACKAGING -> FAILED, releasing stock at most once (guarded by [stockReleased]). */
        fun failFromPackaging(id: Long): Boolean =
            update(
                "status = ?1, stockReleased = true, statusChangedAt = ?2, claimedBy = null, claimedAt = null where id = ?3 and status = ?4 and stockReleased = false",
                PrescriptionStatus.FAILED,
                Instant.now(),
                id,
                PrescriptionStatus.PACKAGING,
            ) == 1

        /** READY -> COMPLETED, but only for the console holding the order. Clears the claim. */
        fun completeFromReady(id: Long, by: String): Boolean =
            update(
                "status = ?1, statusChangedAt = ?2, claimedBy = null, claimedAt = null where id = ?3 and status = ?4 and claimedBy = ?5",
                PrescriptionStatus.COMPLETED,
                Instant.now(),
                id,
                PrescriptionStatus.READY,
                by,
            ) == 1

        /** READY -> READY (called) for the holder: remember this ticket was called out, at most once. */
        fun markCalled(id: Long, by: String): Boolean =
            update(
                "calledAt = ?1 where id = ?2 and status = ?3 and claimedBy = ?4 and calledAt is null",
                Instant.now(),
                id,
                PrescriptionStatus.READY,
                by,
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

        /** Records that a work message was published for this order, so the republisher can spot stalls. */
        fun markPublished(id: Long) {
            update("lastPublishedAt = ?1 where id = ?2", Instant.now(), id)
        }

        /**
         * PACKAGING orders whose work message may have been lost: not published recently.
         * The republisher re-sends them; the packager skips anything already moved.
         */
        fun stalled(since: Instant): List<Order> =
            list(
                "status = ?1 and (lastPublishedAt is null or lastPublishedAt < ?2) order by id",
                PrescriptionStatus.PACKAGING,
                since,
            )

        fun countByStatus(vararg status: PrescriptionStatus): Long {
            return count("status in ?1", status.asList())
        }

        /** Every order the board shows: in flight, ready, or a problem the pharmacist must resolve. */
        fun boardOrders(): List<Order> =
            list("status in ?1 and dismissedAt is null order by statusChangedAt, id", PrescriptionStatus.ON_BOARD.toList())

        /** Problem orders the pharmacist still has to resolve (or dismiss). */
        fun attentionOrders(): List<Order> =
            list("status in ?1 and dismissedAt is null order by statusChangedAt, id", PrescriptionStatus.PROBLEMS.toList())


    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orders_seq")
    @SequenceGenerator(name = "orders_seq", sequenceName = "orders_seq", allocationSize = 1)
    var id: Long? = null

    lateinit var prescriptionCode: String
    var status = PrescriptionStatus.AWAITING_APPROVAL
    var stockReleased = false
    var statusChangedAt: Instant = Instant.now()
    var calledAt: Instant? = null
    var dismissedAt: Instant? = null

    /** Non-null while this order is held, naming the console that holds it. Cleared on the next transition. */
    var claimedBy: String? = null
    var claimedAt: Instant? = null

    /** When we last published a work message (packaging) for this order; drives the republisher. */
    var lastPublishedAt: Instant? = null

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var items: MutableList<OrderItem> = mutableListOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0


}

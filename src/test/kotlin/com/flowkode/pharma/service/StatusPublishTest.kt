package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.dto.StatusPayload
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

@QuarkusTest
class StatusPublishTest {

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var statusConsumer: StatusTestConsumer

    @Inject
    lateinit var statusStream: StatusStream

    private val seededStock = mapOf(1L to 5L, 2L to 10L, 3L to 15L, 4L to 20L, 5L to 25L)

    @BeforeEach
    fun reset() {
        transactional {
            OrderItem.deleteAll()
            Order.deleteAll()
            seededStock.forEach { (id, stock) ->
                Medication.findById(id)!!
                    .apply {
                        this.stock = stock
                        reserved = 0
                    }
            }
        }
        statusConsumer.received.clear()
    }

    @Test
    fun intakePublishesAwaitingApproval() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket

        assertEquals("AWAITING_APPROVAL", awaitStatus(ticket, "AWAITING_APPROVAL").status)
    }

    @Test
    fun approvePublishesPackaging() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        transactional { Order.doTransition(ticket, PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW) }

        orderService.approve(ticket)

        assertEquals("PACKAGING", awaitStatus(ticket, "PACKAGING").status)
    }

    /** The app's own `status-feed` consumer must receive the fanout and push it onto the SSE broadcast. */
    @Test
    fun statusChangeReachesTheBroadcastStream() {
        val broadcasts = CopyOnWriteArrayList<Unit>()
        val subscription = statusStream.changes().subscribe().with { broadcasts.add(it) }
        try {
            orderService.create("P-10000")

            val deadline = System.currentTimeMillis() + 5_000
            while (broadcasts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertFalse(broadcasts.isEmpty(), "board stream never saw the status change")
        } finally {
            subscription.cancel()
        }
    }

    private fun awaitStatus(orderId: Long, status: String): StatusPayload {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            statusConsumer.received.firstOrNull { it.orderId == orderId && it.status == status }
                ?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no $status status event for order $orderId")
    }
}

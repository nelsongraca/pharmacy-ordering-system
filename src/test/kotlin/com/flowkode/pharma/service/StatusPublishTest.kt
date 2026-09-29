package com.flowkode.pharma.service

import com.flowkode.pharma.board.BoardTicket
import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun intakePublishesThePreparingTicket() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

        val published = awaitTicket(ticket, "preparing")
        assertEquals("Pharmacist review", published.stage)
    }

    @Test
    fun approvePublishesPackaging() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        transactional { Order.doTransition(ticket, PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW) }

        orderService.approve(ticket)

        assertEquals("Packing", awaitStage(ticket, "Packing").stage)
    }

    /** The app's own `status-feed` consumer must receive the fanout and push it onto the SSE broadcast. */
    @Test
    fun statusChangeReachesTheBroadcastStream() {
        val broadcasts = CopyOnWriteArrayList<BoardTicket>()
        val subscription = statusStream.changes().subscribe().with { broadcasts.add(it) }
        try {
            val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

            val deadline = System.currentTimeMillis() + 5_000
            while (broadcasts.none { it.id == ticket } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val change = broadcasts.firstOrNull { it.id == ticket }
            assertEquals("preparing", change?.column, "board stream never saw the status change")
        } finally {
            subscription.cancel()
        }
    }

    private fun awaitTicket(orderId: Long, column: String): BoardTicket {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            statusConsumer.received.firstOrNull { it.id == orderId && it.column == column }
                ?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no $column ticket for order $orderId")
    }

    private fun awaitStage(orderId: Long, stage: String): BoardTicket {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            statusConsumer.received.firstOrNull { it.id == orderId && it.stage == stage }
                ?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no $stage ticket for order $orderId")
    }
}

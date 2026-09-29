package com.flowkode.pharma

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.service.OrderResult
import com.flowkode.pharma.service.OrderService
import com.flowkode.pharma.util.transactional
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@QuarkusTest
class BoardResourceTest {

    @Inject
    lateinit var orderService: OrderService

    @field:TestHTTPResource("/board/events")
    lateinit var eventsUrl: URL

    @Suppress("PropertyName")
    private val WHO = "tester"

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
    }

    @Test
    fun boardPageWiresTheSseStream() {
        given().`when`().get("/board")
            .then().statusCode(200)
            .body(containsString("sse-connect=\"/board/events\""))
            .body(containsString("hx-ext=\"sse\""))
    }

    @Test
    fun columnsGroupOrdersAndShowTicketNumbersOnly() {
        val awaiting = (orderService.create("P-10000") as OrderResult.Placed).ticket
        val packing = approvedOrder("P-01000")
        val ready = readyOrder()
        val outOfStock = outOfStockTicket()

        val body = given().`when`().get("/board/columns")
            .then().statusCode(200).extract().asString()

        assertTrue(body.contains(">#$awaiting</span>"))
        assertTrue(body.contains(">#$packing</span>"))
        assertTrue(body.contains(">#$ready</span>"))
        assertTrue(body.contains(">#$outOfStock</span>"))
        assertFalse(body.contains("Amoxicillin")) // the board never shows medications
    }

    @Test
    fun stalePreparingTicketIsMarkedDelayed() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        transactional {
            Order.findById(ticket)!!.statusChangedAt = Instant.now().minus(Duration.ofMinutes(10))
        }

        val body = given().`when`().get("/board/columns")
            .then().statusCode(200).extract().asString()

        assertTrue(body.contains("taking longer than usual"))
    }

    @Test
    fun freshTicketIsNotMarkedDelayed() {
        orderService.create("P-10000")

        val body = given().`when`().get("/board/columns")
            .then().statusCode(200).extract().asString()

        assertFalse(body.contains("taking longer than usual"))
    }

    @Test
    fun calledReadyTicketIsHighlighted() {
        val ticket = readyOrder()
        assertEquals(ticket, orderService.claimNext(PharmacistRole.DELIVERIES, WHO)) // holder calls it
        assertTrue(orderService.call(ticket, WHO))

        val body = given().`when`().get("/board/columns")
            .then().statusCode(200).extract().asString()

        assertTrue(body.contains("Now calling"))
    }

    @Test
    fun dismissedProblemTicketLeavesTheBoard() {
        val ticket = outOfStockTicket()

        val before = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()
        assertTrue(before.contains(">#$ticket</span>"))

        assertTrue(orderService.dismiss(ticket))

        val after = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()
        assertFalse(after.contains(">#$ticket</span>"))
    }

    @Test
    fun completedTicketLeavesTheBoard() {
        val ticket = readyOrder()
        orderService.claimNext(PharmacistRole.DELIVERIES, WHO)
        assertTrue(orderService.handover(ticket, WHO))

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertFalse(body.contains(">#$ticket</span>"))
    }

    @Test
    fun failedTicketAppearsUnderSeePharmacist() {
        val ticket = failedOrder()

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertTrue(body.contains(">#$ticket</span>"))
        assertTrue(body.contains("Failed"))
    }

    @Test
    fun oldCallIsNoLongerHighlighted() {
        val ticket = readyOrder()
        transactional {
            Order.findById(ticket)!!.calledAt = Instant.now().minus(Duration.ofMinutes(2))
        }

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertFalse(body.contains("Now calling"))
    }

    @Test
    fun eventsEndpointStreamsServerSentEvents() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(eventsUrl.toURI()).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        assertEquals(200, response.statusCode())
        val contentType = response.headers().allValues("content-type").firstOrNull().orEmpty()
        assertTrue(contentType.startsWith("text/event-stream"), "was: $contentType")
        response.body().close() // stop the stream; the server ends the subscription
    }

    /**
     * The whole point of the SSE path: while a client is connected, a real status change must
     * arrive as a `changed` event. The subscription is awaited before triggering, so the event
     * cannot be missed (a hot broadcast does not replay).
     */
    @Test
    fun statusChangePushesAChangedEvent() {
        val events = openEventsStream()

        try {
            assertTrue(events.awaitSubscribed(5_000), "never subscribed; saw ${events.seen()}")

            orderService.create("P-10000") // AWAITING_APPROVAL status event

            assertTrue(events.await(20_000), "no `changed` event arrived; saw ${events.seen()}")
        }
        finally {
            events.close()
        }
    }

    /** An idle connection must still receive the 15-second heartbeat. */
    @Test
    fun heartbeatsArriveOnAnIdleStream() {
        val events = openEventsStream()

        try {
            assertTrue(events.awaitPing(25_000), "no `ping` heartbeat arrived; saw ${events.seen()}")
        }
        finally {
            events.close()
        }
    }

    /**
     * Reads the SSE stream on a background thread and watches for `event:changed` and
     * `event:ping`. Values are matched as substrings so a leading space or line-ending
     * variation cannot break the match.
     */
    private fun openEventsStream(): EventStream {
        val reader = eventsUrl.openStream().bufferedReader(StandardCharsets.UTF_8)
        val lines = StringBuilder()
        val subscribed = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val ping = CountDownLatch(1)
        val thread = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lines) { lines.append(line).append('\n') }
                    val event = line.substringAfter("event:", "").trim()
                    if (event.isNotEmpty()) subscribed.countDown()
                    if (event == "changed") changed.countDown()
                    if (event == "ping") ping.countDown()
                }
            }
            catch (_: Exception) {
                // stream closed while we were waiting
            }
        }
        thread.isDaemon = true
        thread.start()
        return EventStream(reader, subscribed, changed, ping, lines)
    }

    /** One open SSE connection, its reader thread, and what it has seen so far. */
    private class EventStream(
        private val reader: BufferedReader,
        private val subscribed: CountDownLatch,
        private val changed: CountDownLatch,
        private val ping: CountDownLatch,
        private val lines: StringBuilder,
    ) {
        /** True once the server has started pushing anything on this stream. */
        fun awaitSubscribed(timeoutMs: Long): Boolean = subscribed.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** True once a `changed` event has arrived. */
        fun await(timeoutMs: Long): Boolean = changed.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** True once a `ping` heartbeat has arrived. */
        fun awaitPing(timeoutMs: Long): Boolean = ping.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun seen(): String = synchronized(lines) { lines.toString().take(500) }

        fun close() {
            reader.close()
        }
    }

    /** Deterministic transitions by id, so they don't depend on which ticket "take next" happens to pick. */
    private fun approvedOrder(code: String): Long {
        val ticket = (orderService.create(code) as OrderResult.Placed).ticket
        transactional { Order.doTransition(ticket, PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW) }
        orderService.approve(ticket)
        return ticket
    }

    private fun readyOrder(): Long {
        val ticket = approvedOrder("P-00100")
        transactional { Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY) }
        return ticket
    }

    private fun failedOrder(): Long {
        val ticket = approvedOrder("P-00009") // valid order, but packaging fails for this code
        transactional { Order.failFromPackaging(ticket) }
        return ticket
    }

    /** P-60000 asks for 6 of medication 1, which has 5 on hand. */
    private fun outOfStockTicket(): Long {
        orderService.create("P-60000")
        return transactional {
            Order.find("status = ?1", PrescriptionStatus.OUT_OF_STOCK).firstResult()!!.id!!
        }
    }
}

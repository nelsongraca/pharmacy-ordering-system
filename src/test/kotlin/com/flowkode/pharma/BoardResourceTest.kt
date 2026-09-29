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
import io.vertx.core.json.JsonObject
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

    private val WHO = "test-console"

    @Inject
    lateinit var orderService: OrderService

    @field:TestHTTPResource("/board/events")
    lateinit var eventsUrl: URL


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
        val awaiting = (orderService.create("P-100000") as OrderResult.Placed).ticket
        val packing = approvedOrder("P-010000")
        val ready = readyOrder()
        val outOfStock = outOfStockTicket()

        val body = given().`when`().get("/board/columns")
            .then().statusCode(200).extract().asString()

        assertTrue(body.contains(">$awaiting</span>"))
        assertTrue(body.contains(">$packing</span>"))
        assertTrue(body.contains(">$ready</span>"))
        assertTrue(body.contains(">$outOfStock</span>"))
        assertFalse(body.contains("Amoxicillin")) // the board never shows medications
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
        assertTrue(before.contains(">$ticket</span>"))

        assertTrue(orderService.dismiss(ticket))

        val after = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()
        assertFalse(after.contains(">$ticket</span>"))
    }

    @Test
    fun completedTicketLeavesTheBoard() {
        val ticket = readyOrder()
        orderService.claimNext(PharmacistRole.DELIVERIES, WHO)
        assertTrue(orderService.handover(ticket, WHO))

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertFalse(body.contains(">$ticket</span>"))
    }

    @Test
    fun failedTicketAppearsUnderSeePharmacist() {
        val ticket = failedOrder()

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertTrue(body.contains(">$ticket</span>"))
        assertTrue(body.contains("Failed"))
    }

    /** A call must stay highlighted however long the patient takes to look up. */
    @Test
    fun aCalledTicketStaysHighlightedIndefinitely() {
        val ticket = readyOrder()
        orderService.claimNext(PharmacistRole.DELIVERIES, WHO)
        assertTrue(orderService.call(ticket, WHO))
        transactional {
            Order.findById(ticket)!!.calledAt = Instant.now().minus(Duration.ofHours(2))
        }

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertTrue(body.contains("Now calling"))
    }

    /** Called tickets sort to the top of Ready, longest-waiting-called first. */
    @Test
    fun calledTicketsSortToTheTopOfReady() {
        val older = readyOrder("P-001000")
        val newer = readyOrder("P-010000")
        orderService.claimNext(PharmacistRole.DELIVERIES, WHO)
        orderService.call(older, WHO)
        transactional {
            Order.findById(older)!!.calledAt = Instant.now().minus(Duration.ofMinutes(5))
            Order.findById(newer)!!.calledAt = Instant.now()
        }

        val body = given().`when`().get("/board/columns").then().statusCode(200).extract().asString()

        assertTrue(body.indexOf(">$older<") < body.indexOf(">$newer<"), "longest-waiting-called should be first")
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
     * The whole point of the SSE path: a status change must arrive as a `ticket` event carrying the
     * ticket's own data, so the browser can place it without re-fetching. The subscription is
     * awaited before triggering, so the event cannot be missed (a hot broadcast does not replay).
     */
    @Test
    fun statusChangePushesTheTicketData() {
        val events = openEventsStream()

        try {
            assertTrue(events.awaitSubscribed(5_000), "never subscribed; saw ${events.seen()}")

            val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

            assertTrue(events.awaitTicket(20_000), "no `ticket` event arrived; saw ${events.seen()}")
            val payload = events.ticketPayload()!!
            assertEquals(ticket, payload.getLong("id"))
            assertEquals("preparing", payload.getString("column"))
            assertEquals("Pharmacist review", payload.getString("stage"))
        }
        finally {
            events.close()
        }
    }

    /** A completed order must be pushed as a `none` ticket so the browser removes it. */
    @Test
    fun completionPushesATicketThatLeavesTheBoard() {
        val ticket = readyOrder()
        orderService.claimNext(PharmacistRole.DELIVERIES, WHO)
        val events = openEventsStream()

        try {
            assertTrue(events.awaitSubscribed(5_000), "never subscribed; saw ${events.seen()}")

            assertTrue(orderService.handover(ticket, WHO))

            assertTrue(events.awaitTicket(20_000), "no `ticket` event arrived; saw ${events.seen()}")
            val payload = events.ticketPayload()!!
            assertEquals(ticket, payload.getLong("id"))
            assertEquals("none", payload.getString("column"))
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
     * Reads the SSE stream on a background thread and watches for `event:ticket` and
     * `event:ping`. Lines are matched as substrings so a leading space or line-ending
     * variation cannot break the match.
     */
    private fun openEventsStream(): EventStream {
        val reader = eventsUrl.openStream().bufferedReader(StandardCharsets.UTF_8)
        val lines = StringBuilder()
        val subscribed = CountDownLatch(1)
        val ticket = CountDownLatch(1)
        val ping = CountDownLatch(1)
        val ticketData = StringBuilder()
        val thread = Thread {
            try {
                var currentEvent = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lines) { lines.append(line).append('\n') }
                    val event = line.substringAfter("event:", "").trim()
                    if (event.isNotEmpty()) {
                        currentEvent = event
                        subscribed.countDown()
                    }
                    // SSE frames are `event:<name>` then `data:<payload>` on the next line.
                    if (line.startsWith("data:") && currentEvent == "ticket") {
                        synchronized(ticketData) {
                            ticketData.setLength(0)
                            ticketData.append(line.removePrefix("data:").trim())
                        }
                        ticket.countDown()
                    }
                    if (currentEvent == "ping") ping.countDown()
                }
            }
            catch (_: Exception) {
                // stream closed while we were waiting
            }
        }
        thread.isDaemon = true
        thread.start()
        return EventStream(reader, subscribed, ticket, ping, ticketData, lines)
    }

    /** One open SSE connection, its reader thread, and what it has seen so far. */
    private class EventStream(
        private val reader: BufferedReader,
        private val subscribed: CountDownLatch,
        private val ticket: CountDownLatch,
        private val ping: CountDownLatch,
        private val ticketData: StringBuilder,
        private val lines: StringBuilder,
    ) {
        /** True once the server has started pushing anything on this stream. */
        fun awaitSubscribed(timeoutMs: Long): Boolean = subscribed.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** True once a `ticket` event has arrived. */
        fun awaitTicket(timeoutMs: Long): Boolean = ticket.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** The JSON payload of the most recent `ticket` event. */
        fun ticketPayload(): JsonObject? = synchronized(ticketData) { ticketData.toString().takeIf { it.isNotEmpty() } }
            ?.let { JsonObject(it) }

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

    private fun readyOrder(code: String = "P-001000"): Long {
        val ticket = approvedOrder(code)
        transactional { Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY) }
        return ticket
    }

    private fun failedOrder(): Long {
        val ticket = approvedOrder("P-000009") // valid order, but packaging fails for this code
        transactional { Order.failFromPackaging(ticket) }
        return ticket
    }

    /** P-600000 asks for 6 of medication 1, which has 5 on hand. */
    private fun outOfStockTicket(): Long {
        orderService.create("P-600000")
        return transactional {
            Order.find("status = ?1", PrescriptionStatus.OUT_OF_STOCK).firstResult()!!.id!!
        }
    }
}

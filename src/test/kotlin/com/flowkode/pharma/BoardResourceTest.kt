package com.flowkode.pharma

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
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
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@QuarkusTest
class BoardResourceTest {

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
        assertTrue(orderService.call(ticket))

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
    fun eventsEndpointStreamsServerSentEvents() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(eventsUrl.toURI()).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        assertEquals(200, response.statusCode())
        val contentType: String = response.headers().firstValue("content-type").orElse("")
        assertTrue(contentType.startsWith("text/event-stream"), "was: $contentType")
        response.body().close() // stop the stream; the server ends the subscription
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

    /** P-60000 asks for 6 of medication 1, which has 5 on hand. */
    private fun outOfStockTicket(): Long {
        orderService.create("P-60000")
        return transactional {
            Order.find("status = ?1", PrescriptionStatus.OUT_OF_STOCK).firstResult()!!.id!!
        }
    }
}

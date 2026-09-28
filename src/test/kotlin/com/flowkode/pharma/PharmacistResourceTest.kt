package com.flowkode.pharma

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.service.OrderResult
import com.flowkode.pharma.service.OrderService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class PharmacistResourceTest {

    @Inject
    lateinit var orderService: OrderService

    private val seededStock = mapOf(1L to 5L, 2L to 10L, 3L to 15L, 4L to 20L, 5L to 25L)

    @BeforeEach
    fun reset() {
        QuarkusTransaction.requiringNew()
            .run {
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
    fun consoleRenders() {
        given()
            .`when`().get("/pharmacist")
            .then()
            .statusCode(200)
            .body(containsString("Take next"))
            .body(containsString("Approvals"))
    }

    @Test
    fun takeReturnsAnOrderCard() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket

        given()
            .`when`().post("/pharmacist/take?role=APPROVALS")
            .then()
            .statusCode(200)
            .body(containsString("Ticket $ticket"))
            .body(containsString("Approve"))
    }

    @Test
    fun takeReturnsNothingForDeliveries() {
        orderService.create("P-10000")

        given()
            .`when`().post("/pharmacist/take?role=DELIVERIES")
            .then()
            .statusCode(200)
            .body(containsString("No orders waiting."))
    }

    @Test
    fun approveMovesTheOrder() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS)

        given()
            .`when`().post("/pharmacist/approve/$ticket")
            .then()
            .statusCode(200)
            .body(containsString("Approved ticket $ticket"))

        QuarkusTransaction.requiringNew()
            .run { assertEquals(PrescriptionStatus.PACKAGING, Order.findById(ticket)!!.status) }
    }

    @Test
    fun countsShowsWaiting() {
        orderService.create("P-10000")

        given()
            .`when`().get("/pharmacist/counts?role=APPROVALS")
            .then()
            .statusCode(200)
            .body(containsString("Waiting for approval: 1"))
    }
}

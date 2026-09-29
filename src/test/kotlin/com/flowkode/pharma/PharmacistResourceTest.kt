package com.flowkode.pharma

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.service.OrderResult
import com.flowkode.pharma.service.OrderService
import com.flowkode.pharma.service.PackagerService
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class PharmacistResourceTest {

    private val WHO = "test-console"

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var packagerService: PackagerService


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
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

        given()
            .`when`().post("/pharmacist/take?role=APPROVALS")
            .then()
            .statusCode(200)
            .body(containsString("Ticket $ticket"))
            .body(containsString("Approve"))
    }

    @Test
    fun takeReturnsNothingForDeliveries() {
        orderService.create("P-100000")

        given()
            .`when`().post("/pharmacist/take?role=DELIVERIES")
            .then()
            .statusCode(200)
            .body(containsString("No orders waiting."))
    }

    @Test
    fun approveMovesTheOrder() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)

        given()
            .`when`().post("/pharmacist/approve/$ticket")
            .then()
            .statusCode(200)
            .body(containsString("Approved ticket $ticket"))

        transactional { assertEquals(PrescriptionStatus.PACKAGING, Order.findById(ticket)!!.status) }
    }

    @Test
    fun deliveryTakeShowsHandoverAndCompletes() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)
        transactional {
            Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }

        given()
            .cookie("console", WHO)
            .`when`()
            .post("/pharmacist/take?role=DELIVERIES")
            .then()
            .statusCode(200)
            .body(containsString("Ticket $ticket"))
            .body(containsString("Handed over"))

        given()
            .cookie("console", WHO)
            .`when`()
            .post("/pharmacist/handover/$ticket")
            .then()
            .statusCode(200)
            .body(containsString("handed over"))

        transactional { assertEquals(PrescriptionStatus.COMPLETED, Order.findById(ticket)!!.status) }
    }

    @Test
    fun countsShowsWaiting() {
        orderService.create("P-100000")

        given()
            .`when`().get("/pharmacist/counts?role=APPROVALS")
            .then()
            .statusCode(200)
            .body(containsString("Waiting: 1"))
    }

    @Test
    fun countsForBothAddsApprovalsAndDeliveries() {
        val ready = (orderService.create("P-200000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ready)
        transactional {
            Order.doTransition(ready, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }
        orderService.create("P-100000") // stays AWAITING_APPROVAL

        given()
            .`when`()
            .get("/pharmacist/counts?role=BOTH")
            .then()
            .statusCode(200)
            .body(containsString("Waiting: 2"))
    }

    @Test
    fun callPatientMarksTheTicketCalled() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)
        transactional { Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY) }

        // the pharmacist takes (claims) it before calling
        given().cookie("console", WHO).`when`().post("/pharmacist/take?role=DELIVERIES").then().statusCode(200)

        given()
            .cookie("console", WHO)
            .`when`().post("/pharmacist/call/$ticket")
            .then()
            .statusCode(200)
            .body(containsString("Calling ticket $ticket"))
            // the ticket stays on screen so the pharmacist can hand it over next
            .body(containsString("Ticket $ticket"))
            .body(containsString("Handed over"))

        // calling again is a harmless no-op: the card stays, no "order changed" message
        given()
            .cookie("console", WHO)
            .`when`().post("/pharmacist/call/$ticket")
            .then()
            .statusCode(200)
            .body(containsString("Ticket $ticket"))
            .body(not(containsString("That order changed")))

        transactional { assertNotNull(Order.findById(ticket)!!.calledAt) }
    }

    @Test
    fun consoleDisablesTakeNextWhileHoldingAnOrder() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

        // first console view: nothing held yet
        given().cookie("console", WHO).`when`().get("/pharmacist?role=APPROVALS")
            .then().statusCode(200)
            .body(not(containsString("disabled")))

        // take an order, then the console must disable "Take next"
        given().cookie("console", WHO).`when`().post("/pharmacist/take?role=APPROVALS").then().statusCode(200)

        given().cookie("console", WHO).`when`().get("/pharmacist?role=APPROVALS")
            .then().statusCode(200)
            .body(containsString("disabled"))
    }

    @Test
    fun takingWhileHoldingReturnsTheSameOrderAgain() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

        val first = given().cookie("console", WHO).`when`().post("/pharmacist/take?role=APPROVALS")
            .then().statusCode(200).extract().asString()
        val second = given().cookie("console", WHO).`when`().post("/pharmacist/take?role=APPROVALS")
            .then().statusCode(200).extract().asString()

        assertTrue(first.contains("Ticket $ticket"))
        assertTrue(second.contains("Ticket $ticket")) // same ticket, not a second one

        transactional {
            assertEquals(1L, Order.count("status = ?1", PrescriptionStatus.IN_REVIEW))
            assertNotNull(Order.findById(ticket)!!.claimedAt)
        }
    }

    /** Two consoles are independent: each can hold its own order (the bug this fixes). */
    @Test
    fun twoConsolesHoldDifferentOrders() {
        val a = (orderService.create("P-100000") as OrderResult.Placed).ticket
        val b = (orderService.create("P-010000") as OrderResult.Placed).ticket

        val console1 = given().cookie("console", "console-1").`when`().post("/pharmacist/take?role=APPROVALS")
            .then().statusCode(200).extract().asString()
        val console2 = given().cookie("console", "console-2").`when`().post("/pharmacist/take?role=APPROVALS")
            .then().statusCode(200).extract().asString()

        assertTrue(console1.contains("Ticket $a"))
        assertTrue(console2.contains("Ticket $b")) // not blocked by console-1's hold
    }

    @Test
    fun attentionListsProblemOrdersAndDismissRemovesThem() {
        orderService.create("P-900000") // 9 Amoxicillin, only 5 on hand -> OUT_OF_STOCK
        val ticket = transactional {
            Order.find("status = ?1", PrescriptionStatus.OUT_OF_STOCK).firstResult()!!.id!!
        }

        given()
            .`when`().get("/pharmacist/attention")
            .then()
            .statusCode(200)
            .body(containsString("OUT_OF_STOCK"))
            .body(containsString("#$ticket"))

        given()
            .`when`().post("/pharmacist/dismiss/$ticket")
            .then()
            .statusCode(200)
            .body(not(containsString("#$ticket")))

        transactional { assertNotNull(Order.findById(ticket)!!.dismissedAt) }
    }
}

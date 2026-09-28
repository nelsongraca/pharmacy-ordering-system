package com.flowkode.pharma

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class KioskResourceTest {

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
    fun kioskPageRendersForm() {
        given()
            .`when`().get("/")
            .then()
            .statusCode(200)
            .body(containsString("Enter your prescription number"))
            .body(containsString("static/bundle"))
            .body(not(containsString("Your number is")))
    }

    @Test
    fun validCodeShowsTicket() {
        given()
            .contentType(ContentType.URLENC).formParam("code", "P-10000")
            .`when`().post("/orders")
            .then()
            .statusCode(200)
            .body(containsString("Your number is"))
    }

    @Test
    fun malformedCodeRerendersForm() {
        given()
            .contentType(ContentType.URLENC).formParam("code", "nope")
            .`when`().post("/orders")
            .then()
            .statusCode(200)
            .body(containsString("look like a prescription number"))
    }

    @Test
    fun outOfStockRerendersForm() {
        given()
            .contentType(ContentType.URLENC).formParam("code", "P-90000")
            .`when`().post("/orders")
            .then()
            .statusCode(200)
            .body(containsString("Please ask at the counter"))
    }
}

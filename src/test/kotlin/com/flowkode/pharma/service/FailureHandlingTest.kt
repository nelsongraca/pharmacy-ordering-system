package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import io.vertx.core.json.JsonObject
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class FailureHandlingTest {

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var packagerService: PackagerService

    @Inject
    lateinit var deadLetterService: DeadLetterService

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
    fun sourceOutageIsReportedAndCreatesNothing() {
        assertEquals(OrderResult.Unavailable, orderService.create("P-99999"))

        transactional { assertEquals(0L, Order.count()) }
    }

    @Test
    fun duplicateActivePrescriptionIsRejected() {
        assertTrue(orderService.create("P-10000") is OrderResult.Placed)

        assertEquals(OrderResult.AlreadyActive, orderService.create("P-10000"))
        transactional { assertEquals(1L, Order.count()) }
    }

    @Test
    fun packagerFailureEndsFailedAndReleasesStockOnce() {
        val ticket = (orderService.create("P-00009") as OrderResult.Placed).ticket // 9 of medication 5
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)

        assertThrows(IllegalStateException::class.java) {
            packagerService.consume(JsonObject().put("orderId", ticket))
        }

        deadLetterService.consume(JsonObject().put("orderId", ticket))
        deadLetterService.consume(JsonObject().put("orderId", ticket)) // a duplicate DLQ delivery must not release twice

        transactional {
            assertEquals(PrescriptionStatus.FAILED, Order.findById(ticket)!!.status)
            assertEquals(0L, Medication.findById(5L)!!.reserved)
            assertEquals(25L, Medication.findById(5L)!!.stock)
        }
    }
}

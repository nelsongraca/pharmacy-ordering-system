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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The packager consumer must ack-and-skip anything that is not (still) PACKAGING, so duplicates are harmless. */
@QuarkusTest
class PackagerServiceTest {

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var packagerService: PackagerService

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
    fun ignoresAnOrderThatHasNotBeenApproved() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket

        packagerService.consume(JsonObject().put("orderId", ticket))

        transactional { assertEquals(PrescriptionStatus.AWAITING_APPROVAL, Order.findById(ticket)!!.status) }
    }

    @Test
    fun ignoresAnOrderAlreadyMarkedReady() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)
        transactional { Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY) }

        packagerService.consume(JsonObject().put("orderId", ticket))

        transactional { assertEquals(PrescriptionStatus.READY, Order.findById(ticket)!!.status) }
    }
}

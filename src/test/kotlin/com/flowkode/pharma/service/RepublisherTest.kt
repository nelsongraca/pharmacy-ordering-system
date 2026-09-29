package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.OrderItem
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class RepublisherTest {

    private val WHO = "test-console"

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var republisher: Republisher

    @Inject
    lateinit var packagingConsumer: PackagingTestConsumer

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
        packagingConsumer.received.clear()
    }

    @Test
    fun approvingRecordsThePublish() {
        val ticket = pacingOrder()

        assertNotNull(transactional { Order.findById(ticket)!!.lastPublishedAt })
    }

    @Test
    fun aFreshlyPublishedOrderIsNotRepublished() {
        val ticket = pacingOrder()
        awaitPackaging(ticket) // let the original approve publish land, then forget it
        packagingConsumer.received.clear()

        republisher.republishStalled()

        // give the republisher a moment; it should find nothing stale
        Thread.sleep(300)
        assertFalse(packagingConsumer.received.any { it.orderId == ticket })
    }

    @Test
    fun aStalledPackagingOrderIsRepublished() {
        val ticket = pacingOrder()
        awaitPackaging(ticket) // let the original approve publish land, then forget it
        packagingConsumer.received.clear()
        // pretend the original publish was long ago
        transactional { Order.findById(ticket)!!.lastPublishedAt = Instant.now().minusSeconds(120) }

        republisher.republishStalled()

        assertTrue(awaitPackaging(ticket), "stalled order was not republished")
    }

    /** Wait for a packaging message for [ticket] to reach the test consumer. */
    private fun awaitPackaging(ticket: Long): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (packagingConsumer.received.any { it.orderId == ticket }) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Create an order and move it to PACKAGING via a real approve, so lastPublishedAt is set. */
    private fun pacingOrder(): Long {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        assertEquals(ticket, orderService.claimNext(PharmacistRole.APPROVALS, WHO))
        assertTrue(orderService.approve(ticket))
        return ticket
    }
}

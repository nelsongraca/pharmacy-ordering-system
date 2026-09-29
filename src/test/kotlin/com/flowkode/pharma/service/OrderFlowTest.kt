package com.flowkode.pharma.service

import com.flowkode.pharma.domain.*
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class OrderFlowTest {

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

    /** Spec happy path: submit, approve, pack, deliver, hand over. */
    @Test
    fun happyPathPacksAndHandsOver() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket

        assertEquals(ticket, orderService.claimNext(PharmacistRole.APPROVALS, WHO))
        assertTrue(orderService.approve(ticket))
        assertTrue(transactional {
            Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        })

        assertEquals(ticket, orderService.claimNext(PharmacistRole.DELIVERIES, WHO))
        assertTrue(orderService.handover(ticket, WHO))

        transactional {
            assertEquals(PrescriptionStatus.COMPLETED, Order.findById(ticket)!!.status)
            assertEquals(4L, Medication.findById(1L)!!.stock)
            assertEquals(0L, Medication.findById(1L)!!.reserved)
        }
    }

    @Test
    fun duplicatePackagingMessageChangesNothing() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)

        assertTrue(transactional {
            Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        })
        assertFalse(transactional {
            Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        })

        transactional { assertEquals(PrescriptionStatus.READY, Order.findById(ticket)!!.status) }
    }

    @Test
    fun handoverOnAReadyOrderConsumesStockOnlyOnce() {
        val ticket = (orderService.create("P-100000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS, WHO)
        orderService.approve(ticket)
        transactional {
            Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }

        // the delivery pharmacist must claim it before it can be handed over
        assertEquals(ticket, orderService.claimNext(PharmacistRole.DELIVERIES, WHO))
        assertTrue(orderService.handover(ticket, WHO))
        assertFalse(orderService.handover(ticket, WHO))

        transactional { assertEquals(4L, Medication.findById(1L)!!.stock) }
    }

    /** One AWAITING_APPROVAL and one READY order: the `status in ?1` count must handle both one and two values. */
    @Test
    fun countByStatusHandlesOneAndManyStatuses() {
        val ready = (orderService.create("P-200000") as OrderResult.Placed).ticket
        assertEquals(ready, orderService.claimNext(PharmacistRole.APPROVALS, WHO))
        orderService.approve(ready)
        transactional {
            Order.doTransition(ready, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }

        orderService.create("P-100000") // stays AWAITING_APPROVAL

        transactional {
            assertEquals(1L, Order.countByStatus(PrescriptionStatus.AWAITING_APPROVAL))
            assertEquals(1L, Order.countByStatus(PrescriptionStatus.READY))
            assertEquals(2L, Order.countByStatus(PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.READY))
        }
    }
}

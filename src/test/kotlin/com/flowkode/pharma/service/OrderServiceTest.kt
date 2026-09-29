package com.flowkode.pharma.service

import com.flowkode.pharma.domain.*
import com.flowkode.pharma.dto.OrderPayload
import com.flowkode.pharma.util.transactional
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
class OrderServiceTest {

    @Inject
    lateinit var orderService: OrderService

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
    fun onlyValidNumber() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            orderService.create("randomText")
        }
        // Probably better to use validation API, not spending more time on validation
        assertEquals("Invalid prescription number", ex.message)
    }

    @Test
    fun allZeroNumberIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) {
            orderService.create("P-00000")
        }
    }

    @Test
    @Transactional
    fun validPrescriptionReservesStockAndCreatesOrder() {
        val result = orderService.create("P-10000")

        assertTrue(result is OrderResult.Placed)

        assertEquals(1L, Medication.findById(1L)!!.reserved)
        assertEquals(5L, Medication.findById(1L)!!.stock)

        val order = Order.findById((result as OrderResult.Placed).ticket)!!
        assertEquals(PrescriptionStatus.AWAITING_APPROVAL, order.status)
        assertEquals(1, order.items.size)
        assertEquals(1L, order.items[0].amount)
    }

    @Test
    fun sixDigitCodeOrdersTheFavoriteMedication() {
        transactional {
            Medication.findById(6L)!!.apply {
                stock = 30
                reserved = 0
            }
        }

        val result = orderService.create("P-000001") // 1 of medication 6

        assertTrue(result is OrderResult.Placed)
        transactional {
            assertEquals(1L, Medication.findById(6L)!!.reserved)
            val order = Order.findById((result as OrderResult.Placed).ticket)!!
            assertEquals(6L, order.items[0].medication.id)
        }
    }

    @Test
    @Transactional
    fun shortageLeavesNothingReservedAndRecordsOutOfStock() {
        val result = orderService.create("P-90000") // 9 Amoxicillin, only 5 on hand

        assertEquals(OrderResult.OutOfStock(1L), result)
        assertEquals(0L, Medication.findById(1L)!!.reserved)
        assertEquals(1L, Order.count("status = ?1", PrescriptionStatus.OUT_OF_STOCK))
    }

    @Test
    fun reservationIsAllOrNothing() {
        transactional { Medication.findById(2L)!!.stock = 8L }

        val result = orderService.create("P-19000") // 1 Amoxicillin (ok) + 9 Ibuprofen (only 8)

        assertEquals(OrderResult.OutOfStock(2L), result)
        transactional {
            assertEquals(0L, Medication.findById(1L)!!.reserved)
            assertEquals(0L, Medication.findById(2L)!!.reserved)
        }
    }

    @Test
    fun lastBoxRaceOnlyOneOrderWins() {
        transactional { Medication.findById(1L)!!.stock = 1L }

        val threads = 10
        // distinct prescriptions, each wanting one unit of medication 1 and a different amount of medication 5
        val codes = (0..9).map { "P-1000$it" }
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        try {
            val futures = codes.map { code ->
                pool.submit(Callable {
                    latch.await()
                    orderService.create(code)
                })
            }
            latch.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is OrderResult.Placed })
            assertEquals(9, results.count { it is OrderResult.OutOfStock })
        }
        finally {
            pool.shutdown()
        }

        transactional {
            assertEquals(1L, Medication.findById(1L)!!.reserved)
        }
    }

    @Test
    fun claimNextReturnsOldestFirstAndNullWhenEmpty() {
        val first = (orderService.create("P-10000") as OrderResult.Placed).ticket
        val second = (orderService.create("P-20000") as OrderResult.Placed).ticket

        assertEquals(first, orderService.claimNext(PharmacistRole.APPROVALS))
        assertEquals(second, orderService.claimNext(PharmacistRole.APPROVALS))
        assertNull(orderService.claimNext(PharmacistRole.APPROVALS))
    }

    @Test
    fun parallelClaimHandsEachOrderOutOnce() {
        // one unit each of five different medications, so five distinct orders exist to be claimed
        val codes = listOf("P-10000", "P-01000", "P-00100", "P-00010", "P-00001")
        val tickets = codes.map { (orderService.create(it) as OrderResult.Placed).ticket }

        val threads = 10
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                pool.submit(Callable {
                    latch.await()
                    orderService.claimNext(PharmacistRole.APPROVALS)
                })
            }
            latch.countDown()
            val claimed = futures.mapNotNull { it.get(30, TimeUnit.SECONDS) }

            assertEquals(5, claimed.size)
            assertEquals(tickets.toSet(), claimed.toSet())
        }
        finally {
            pool.shutdown()
        }
    }

    @Test
    fun approveMovesToPackagingAndPublishes() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        assertEquals(ticket, orderService.claimNext(PharmacistRole.APPROVALS))

        assertTrue(orderService.approve(ticket))

        assertEquals(OrderPayload(ticket), awaitPackaging())
        transactional { assertEquals(PrescriptionStatus.PACKAGING, Order.findById(ticket)!!.status) }
    }

    @Test
    fun rejectReleasesStockExactlyOnce() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS)

        assertTrue(orderService.reject(ticket))
        assertFalse(orderService.reject(ticket))

        transactional {
            assertEquals(0L, Medication.findById(1L)!!.reserved)
            assertEquals(PrescriptionStatus.REJECTED, Order.findById(ticket)!!.status)
        }
    }

    @Test
    fun bothRolePrefersDeliveryOverApprovals() {
        val ready = readyOrder("P-01000")
        orderService.create("P-10000") // waits for approvals

        assertEquals(ready, orderService.claimNext(PharmacistRole.BOTH))
    }

    @Test
    fun deliveriesRoleIgnoresApprovals() {
        orderService.create("P-10000")

        assertNull(orderService.claimNext(PharmacistRole.DELIVERIES))
    }

    @Test
    fun approvalsRoleIgnoresReady() {
        readyOrder("P-10000")

        assertNull(orderService.claimNext(PharmacistRole.APPROVALS))
    }

    @Test
    fun samePrescriptionCanBeOrderedAgainAfterItCompletes() {
        val first = readyOrder("P-10000")
        assertEquals(first, orderService.claimNext(PharmacistRole.DELIVERIES))
        assertTrue(orderService.handover(first))

        assertTrue(orderService.create("P-10000") is OrderResult.Placed)
    }

    @Test
    fun callIsIdempotent() {
        val ticket = readyOrder("P-10000")

        assertTrue(orderService.call(ticket))
        assertFalse(orderService.call(ticket))
    }

    @Test
    fun dismissIsIdempotent() {
        orderService.create("P-90000") // 9 Amoxicillin, only 5 on hand -> OUT_OF_STOCK
        val ticket = transactional {
            Order.find("status = ?1", PrescriptionStatus.OUT_OF_STOCK).firstResult()!!.id!!
        }

        assertTrue(orderService.dismiss(ticket))
        assertFalse(orderService.dismiss(ticket))
    }

    @Test
    fun approveAfterRejectIsANoOp() {
        val ticket = (orderService.create("P-10000") as OrderResult.Placed).ticket
        orderService.claimNext(PharmacistRole.APPROVALS)

        assertTrue(orderService.reject(ticket))
        assertFalse(orderService.approve(ticket))
    }

    private fun readyOrder(code: String): Long {
        val ticket = (orderService.create(code) as OrderResult.Placed).ticket
        transactional { Order.doTransition(ticket, PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW) }
        orderService.approve(ticket)
        transactional { Order.doTransition(ticket, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY) }
        return ticket
    }

    private fun awaitPackaging(): OrderPayload {
        val deadline = System.currentTimeMillis() + 50000000
        while (System.currentTimeMillis() < deadline) {
            packagingConsumer.received.firstOrNull()
                ?.let { return it }
            Thread.sleep(50)
        }
        fail<OrderPayload>("no message on orders.packaging")
        error("unreachable")
    }
}

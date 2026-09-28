package com.flowkode.pharma.service

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
class OrderServiceTest {

    @Inject
    lateinit var orderService: OrderService

    @Test
    fun onlyValidNumber() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            orderService.order("randomText")
        }
        // Probably better to use validation API, not spending more time on validation
        assertEquals("Invalid prescription number", ex.message)
    }

}

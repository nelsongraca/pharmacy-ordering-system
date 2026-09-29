package com.flowkode.pharma.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pure unit tests for the local prescription decoder: no Quarkus, no DB. */
class PrescriptionServiceTest {

    private val service = PrescriptionService()

    @Test
    fun decodesFiveDigits() {
        assertEquals(mapOf(1L to 1L), service.fetchPrescription("P-10000"))
    }

    @Test
    fun decodesSixDigitsForTheFavoriteMedication() {
        assertEquals(mapOf(1L to 1L, 6L to 2L), service.fetchPrescription("P-100002"))
    }

    @Test
    fun trimsAndUppercases() {
        assertEquals(mapOf(2L to 3L), service.fetchPrescription("  p-03000  "))
    }

    @Test
    fun dropsZeroDigits() {
        assertEquals(mapOf(2L to 5L, 4L to 1L), service.fetchPrescription("P-05010"))
    }

    @Test
    fun allZerosIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { service.fetchPrescription("P-00000") }
        assertThrows(IllegalArgumentException::class.java) { service.fetchPrescription("P-000000") }
    }

    @Test
    fun blankIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { service.fetchPrescription("   ") }
    }

    @Test
    fun malformedIsInvalid() {
        listOf("P-1234", "12345", "P-1234567", "X-10000", "P-1O000").forEach {
            assertThrows(IllegalArgumentException::class.java, { service.fetchPrescription(it) }, it)
        }
    }

    @Test
    fun sourceOutageCodeThrows() {
        assertThrows(PrescriptionUnavailableException::class.java) { service.fetchPrescription("P-99999") }
    }
}

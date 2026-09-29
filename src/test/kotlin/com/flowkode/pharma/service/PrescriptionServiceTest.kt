package com.flowkode.pharma.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pure unit tests for the local prescription decoder: no Quarkus, no DB. */
class PrescriptionServiceTest {

    private val service = PrescriptionService()

    @Test
    fun decodesSixDigits() {
        assertEquals(mapOf(1L to 1L), service.fetchPrescription("P-100000"))
    }

    @Test
    fun decodesTheFavoriteMedication() {
        assertEquals(mapOf(1L to 1L, 6L to 2L), service.fetchPrescription("P-100002"))
    }

    @Test
    fun trimsAndUppercases() {
        assertEquals(mapOf(2L to 3L), service.fetchPrescription("  p-030000  "))
    }

    @Test
    fun dropsZeroDigits() {
        assertEquals(mapOf(2L to 5L, 4L to 1L), service.fetchPrescription("P-050100"))
    }

    @Test
    fun allZerosIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { service.fetchPrescription("P-000000") }
    }

    @Test
    fun blankIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { service.fetchPrescription("   ") }
    }

    @Test
    fun malformedIsInvalid() {
        listOf("P-12345", "P-1234", "123456", "P-1234567", "X-100000", "P-1O0000").forEach {
            assertThrows(IllegalArgumentException::class.java, { service.fetchPrescription(it) }, it)
        }
    }

    @Test
    fun sourceOutageCodeThrows() {
        assertThrows(PrescriptionUnavailableException::class.java) { service.fetchPrescription("P-999999") }
    }
}

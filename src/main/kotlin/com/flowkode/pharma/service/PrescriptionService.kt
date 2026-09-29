package com.flowkode.pharma.service

import jakarta.enterprise.context.ApplicationScoped

/*
this would either be our external service for fetching prescription information or database access to fetch the data
here we just "decode" from the number, one digit per medication id starting at 1
P-100000 would mean 1 of the medication with id 1
P-600000 would mean 6 of the medication with id 1
P-000001 would mean 1 of the medication with id 6 (our "favorite" medication)
always six digits, one per medication id; yes it limits to 9 per line, for this exercise it's enough
*/
@ApplicationScoped
class PrescriptionService {

    // exactly six digits, one per medication id 1..6
    private val extractionPattern = Regex("^P-([0-9])([0-9])([0-9])([0-9])([0-9])([0-9])$")

    /** medication id -> quantity, one digit per id starting at 1. */
    fun fetchPrescription(prescriptionNumber: String): Map<Long, Long> {
        val code = prescriptionNumber.trim().uppercase()

        // Demo hook: pretend the external prescription source is down.
        if (code == UNAVAILABLE_CODE) throw PrescriptionUnavailableException()

        val match = extractionPattern.matchEntire(code)
            ?: throw IllegalArgumentException("Invalid prescription number")

        val items = match.groupValues
            .drop(1) // Skips index 0 (full match)
            .mapIndexedNotNull { index, digit ->
                // an unmatched optional group is an empty string
                digit.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { (index + 1).toLong() to it.toLong() }
            }
            .toMap()

        if (items.isEmpty()) throw IllegalArgumentException("Invalid prescription number")
        return items
    }

    companion object {
        private const val UNAVAILABLE_CODE = "P-999999"
    }
}

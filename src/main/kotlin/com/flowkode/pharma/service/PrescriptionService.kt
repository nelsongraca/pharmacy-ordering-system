package com.flowkode.pharma.service

import jakarta.enterprise.context.ApplicationScoped

/*
this would either be our external service for fetching prescription information or database access to fetch the data
here we just "decode" from the number
P-10000 would mean 1 of the medication with id 1
P-60000 would mean 6 of the medication with id 1
yes it limits to 9, for this exercise it's enough
*/
@ApplicationScoped
class PrescriptionService {

    private val extractionPattern = Regex("^P-([0-9])([0-9])([0-9])([0-9])([0-9])$")

    /** medication id -> quantity, one digit per id starting at 1. */
    fun fetchPrescription(prescriptionNumber: String): Map<Long, Long> {
        val code = prescriptionNumber.trim().uppercase()

        // Demo hook: pretend the external prescription source is down.
        if (code == UNAVAILABLE_CODE) throw PrescriptionUnavailableException()

        val match = extractionPattern.matchEntire(code)
            ?: throw IllegalArgumentException("Invalid prescription number")

        val items = match.groupValues
            .drop(1) // Skips index 0 (full match)
            .mapIndexed { index, digit -> (index + 1).toLong() to digit.toLong() }
            .filter { (_, quantity) -> quantity > 0 }
            .toMap()

        if (items.isEmpty()) throw IllegalArgumentException("Invalid prescription number")
        return items
    }

    companion object {
        private const val UNAVAILABLE_CODE = "P-99999"
    }
}

package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import jakarta.enterprise.context.ApplicationScoped

/*
this would either be our external service for fetching prescription information or database access to fetch the data
here we just "decode" from the number
P-1000 would mean 1 of the medication with id 1
P-6000 would mean 6 of the medication with id 1
yes it limits to 9, for this exercise it's enough
*/
@ApplicationScoped
class PrescriptionService {

    private val extractionPattern = Regex("^P-([0-9])([0-9])([0-9])([0-9])([0-9])$")

    fun fetchPrescription(prescriptionNumber: String): Map<Medication?, Long> {
        //these are coded to fetch specific medications by their index.
        val result = extractionPattern.matchEntire(prescriptionNumber)
        if (result != null) {

            return result.groupValues
                .drop(1) // Skips index 0 (full match)
                .mapIndexed { index, groupValue ->
                    Medication.findById(index.toLong()) to groupValue.toLong()
                }
                .toMap()
        }
        throw IllegalArgumentException("Invalid prescription number")
    }
}

package com.flowkode.pharma.service

import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class OrderService(
    val prescriptionService: PrescriptionService
) {

    fun order(prescriptionNumber: String) {
        val parsedOrder = prescriptionService.fetchPrescription(prescriptionNumber)


    }


}
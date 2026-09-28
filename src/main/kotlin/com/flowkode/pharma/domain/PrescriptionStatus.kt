package com.flowkode.pharma.domain

enum class PrescriptionStatus {
    RECEIVED,
    OUT_OF_STOCK,
    AWAITING_APPROVAL,
    IN_REVIEW,
    REJECTED,
    PACKAGING,
    READY,
    COMPLETED,
    FAILED,
}
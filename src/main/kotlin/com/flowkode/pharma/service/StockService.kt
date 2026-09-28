package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Medication
import com.flowkode.pharma.domain.Order
import jakarta.enterprise.context.ApplicationScoped

//todo: think if we want this or merged somewhere else
@ApplicationScoped
class StockService {

    fun release(order: Order) {
        for (item in order.items) {
            Medication.release(item.medication.id!!, item.amount)
        }
    }
}

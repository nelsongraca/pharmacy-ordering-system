package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.dto.OrderPayload
import com.flowkode.pharma.util.transactional
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming

/** Consumes orders the packager could not process, so they fail visibly instead of silently. */
@ApplicationScoped
class DeadLetterService(
    private val stockService: StockService,
    private val statusPublisher: StatusPublisher,
) {

    @Incoming("packaging-dlq")
    fun consume(payload: JsonObject) {
        val orderId = payload.mapTo(OrderPayload::class.java).orderId

        val failed = transactional {
            if (!Order.failFromPackaging(orderId)) return@transactional false

            stockService.release(Order.findById(orderId)!!)
            true
        }

        if (failed) statusPublisher.publish(orderId, PrescriptionStatus.FAILED)
    }
}

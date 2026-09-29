package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.dto.OrderPayload
import com.flowkode.pharma.util.transactional
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.ThreadLocalRandom

@ApplicationScoped
class PackagerService(private val statusPublisher: StatusPublisher) {

    companion object {

        private const val DELAY_MIN_MS = 3_000L
        private const val DELAY_MAX_MS = 8_000L

        /** Demo hook: an order for this code always fails packaging, so it lands in the DLQ. */
        private const val FAIL_CODE = "P-00009"
    }


    @Incoming("packager")
    fun consume(payload: JsonObject) {
        val orderId = payload.mapTo(OrderPayload::class.java).orderId

        // Only pack orders still in PACKAGING; duplicates and already-moved orders are a no-op.
        val order = transactional {
            Order.findById(orderId)?.let { it.prescriptionCode to it.status }
        }
        if (order == null || order.second != PrescriptionStatus.PACKAGING) return

        if (order.first == FAIL_CODE) throw IllegalStateException("Simulated packaging failure for ${order.first}")

        // simulate the packing work outside any transaction, so no connection is held while "working"
        Thread.sleep(
            ThreadLocalRandom.current()
                .nextLong(DELAY_MIN_MS, DELAY_MAX_MS + 1)
        )
        val ready = transactional {
            Order.doTransition(orderId, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }
        if (ready) statusPublisher.publish(orderId, PrescriptionStatus.READY)
    }
}

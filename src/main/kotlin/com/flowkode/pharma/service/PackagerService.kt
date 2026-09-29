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
class PackagerService {

    companion object {

        private const val DELAY_MIN_MS = 3_000L
        private const val DELAY_MAX_MS = 8_000L
    }


    @Incoming("packager")
    fun consume(payload: JsonObject) {
        val orderId = payload.mapTo(OrderPayload::class.java).orderId

        // simulate the packing work outside any transaction, so no connection is held while "working"
        Thread.sleep(
            ThreadLocalRandom.current()
                .nextLong(DELAY_MIN_MS, DELAY_MAX_MS + 1)
        )
        transactional {
            Order.doTransition(orderId, PrescriptionStatus.PACKAGING, PrescriptionStatus.READY)
        }
    }
}

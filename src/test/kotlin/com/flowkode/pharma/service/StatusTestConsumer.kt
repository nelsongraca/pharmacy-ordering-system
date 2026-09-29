package com.flowkode.pharma.service

import com.flowkode.pharma.dto.StatusPayload
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only consumer: records what lands on the `orders.status` fanout. */
@ApplicationScoped
class StatusTestConsumer {

    val received = CopyOnWriteArrayList<StatusPayload>()

    @Incoming("status-test")
    fun consume(payload: JsonObject) {
        received.add(payload.mapTo(StatusPayload::class.java))
    }
}

package com.flowkode.pharma.service

import com.flowkode.pharma.dto.OrderPayload
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only consumer: records what lands on the packaging routing key. */
@ApplicationScoped
class PackagingTestConsumer {

    val received = CopyOnWriteArrayList<OrderPayload>()

    @Incoming("packaging-test")
    fun consume(payload: JsonObject) {
        received.add(payload.mapTo(OrderPayload::class.java))
    }
}

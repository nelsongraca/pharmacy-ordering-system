package com.flowkode.pharma.service

import com.flowkode.pharma.board.BoardTicket
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only consumer: records what lands on the `orders.status` fanout. */
@ApplicationScoped
class StatusTestConsumer {

    val received = CopyOnWriteArrayList<BoardTicket>()

    @Incoming("status-test")
    fun consume(payload: JsonObject) {
        received.add(payload.mapTo(BoardTicket::class.java))
    }
}

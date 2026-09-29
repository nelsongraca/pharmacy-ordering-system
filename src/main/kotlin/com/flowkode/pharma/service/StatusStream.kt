package com.flowkode.pharma.service

import com.flowkode.pharma.board.BoardTicket
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming

/**
 * This instance's copy of every `orders.status` event, fanned out to the board's SSE clients.
 * The ticket data is forwarded as-is, so the board pushes it to browsers instead of re-fetching.
 */
@ApplicationScoped
class StatusStream {

    // Hot stream: every subscriber (every open SSE connection) sees every event.
    private val changes = BroadcastProcessor.create<BoardTicket>()

    fun changes(): Multi<BoardTicket> = changes

    @Incoming("status-feed")
    fun onStatus(payload: JsonObject) {
        changes.onNext(payload.mapTo(BoardTicket::class.java))
    }
}

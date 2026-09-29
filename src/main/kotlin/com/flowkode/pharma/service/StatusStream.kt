package com.flowkode.pharma.service

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming

/**
 * This instance's copy of every `orders.status` event, fanned out to the board's SSE clients.
 * The payload is ignored: the board reloads its state from the DB on any signal.
 */
@ApplicationScoped
class StatusStream {

    // Hot stream: every subscriber (every open SSE connection) sees every event.
    private val changes = BroadcastProcessor.create<Unit>()

    fun changes(): Multi<Unit> = changes

    @Incoming("status-feed")
    fun onStatus(@Suppress("UNUSED_PARAMETER") payload: JsonObject) {
        changes.onNext(Unit)
    }
}

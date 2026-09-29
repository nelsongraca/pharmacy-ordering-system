package com.flowkode.pharma.service

import com.flowkode.pharma.board.BoardTicket
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

/**
 * Publishes a ticket's new shape to the `orders.status` fanout that feeds the live board. Call it
 * only after the DB transaction commits. The message carries everything the board needs, so no
 * board ever re-reads the database.
 */
@ApplicationScoped
class StatusPublisher {

    private val log = Logger.getLogger(StatusPublisher::class.java)

    @Channel("status")
    lateinit var status: Emitter<BoardTicket>

    fun publish(ticket: BoardTicket) {
        try {
            this.status.send(ticket)
        } catch (ex: Exception) {
            // The DB is the source of truth; a lost event only means the board is briefly stale.
            log.errorf(ex, "Failed to publish status for order %d", ticket.id)
        }
    }
}

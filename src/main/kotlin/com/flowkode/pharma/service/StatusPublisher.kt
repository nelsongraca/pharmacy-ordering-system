package com.flowkode.pharma.service

import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.dto.StatusPayload
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

/** Publishes to the `orders.status` fanout that feeds the live board. Call it only after the DB transaction commits. */
@ApplicationScoped
class StatusPublisher {

    private val log = Logger.getLogger(StatusPublisher::class.java)

    @Channel("status")
    lateinit var status: Emitter<StatusPayload>

    fun publish(orderId: Long, status: PrescriptionStatus) {
        try {
            this.status.send(StatusPayload(orderId, status.name))
        } catch (ex: Exception) {
            // The DB is the source of truth; a lost event only means the board waits for its next poll.
            log.errorf(ex, "Failed to publish status %s for order %d", status, orderId)
        }
    }
}

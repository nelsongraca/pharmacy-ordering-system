package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.dto.OrderPayload
import com.flowkode.pharma.util.transactional
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

/**
 * The only thing that touches the packaging work queue: sends the work message and records when,
 * so the [Republisher] can recover a message lost between commit and publish. The DB stays the
 * source of truth.
 */
@ApplicationScoped
class PackagingPublisher {

    private val log = Logger.getLogger(PackagingPublisher::class.java)

    @Channel("packaging")
    lateinit var packaging: Emitter<OrderPayload>

    fun publish(orderId: Long) {
        try {
            packaging.send(OrderPayload(orderId))
            transactional { Order.markPublished(orderId) }
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to publish packaging for order %d", orderId)
        }
    }
}

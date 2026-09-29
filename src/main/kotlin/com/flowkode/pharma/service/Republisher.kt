package com.flowkode.pharma.service

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.util.transactional
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Recovers from the one work message that can be lost after its DB commit: `PACKAGING` orders
 * whose `orders.packaging` message never made it onto the broker (a crash between commit and
 * publish, or a broker error). Every run re-sends orders still in `PACKAGING` that were not
 * published recently; the packager skips anything not in `PACKAGING`, so duplicates are harmless.
 *
 * The other two waiting states need no resend: `AWAITING_APPROVAL` is picked up by a pharmacist
 * on demand, and `READY` delivery is claimed straight from the database.
 */
@ApplicationScoped
class Republisher(private val packagingPublisher: PackagingPublisher) {

    private val log = Logger.getLogger(Republisher::class.java)

    companion object {
        private const val REPUBLISH_AFTER_MS = 30_000L
    }

    @Scheduled(every = "30s")
    fun republishStalled() {
        val cutoff = Instant.now().minusMillis(REPUBLISH_AFTER_MS)
        val stalled = transactional { Order.stalled(cutoff).map { it.id!! } }

        if (stalled.isNotEmpty()) {
            log.infof("Republishing packaging for stalled orders %s", stalled)
            stalled.forEach { packagingPublisher.publish(it) }
        }
    }
}

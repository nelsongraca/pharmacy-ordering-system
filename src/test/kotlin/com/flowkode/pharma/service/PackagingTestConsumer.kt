package com.flowkode.pharma.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only consumer: records what lands on the packaging routing key. */
@ApplicationScoped
class PackagingTestConsumer {

    val received = CopyOnWriteArrayList<String>()

    @Incoming("packaging-test")
    fun consume(message: String) {
        received.add(message)
    }
}

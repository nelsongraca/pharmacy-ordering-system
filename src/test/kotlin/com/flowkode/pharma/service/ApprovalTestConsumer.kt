package com.flowkode.pharma.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only consumer: records what lands on the approval routing key. */
@ApplicationScoped
class ApprovalTestConsumer {

    val received = CopyOnWriteArrayList<String>()

    @Incoming("approval-test")
    fun consume(message: String) {
        received.add(message)
    }
}

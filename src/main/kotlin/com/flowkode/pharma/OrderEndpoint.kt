package com.flowkode.pharma

import com.flowkode.pharma.service.OrderService
import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestForm

@Path("/order")
class OrderEndpoint() {

    @Inject
    @Location("message")
    lateinit var message: Template

    @Inject
    lateinit var orderService: OrderService

    @POST
    @Path("/submit")
    @Produces(MediaType.TEXT_HTML)
    fun get(@RestForm("prescription_number") prescriptionNumber: String): TemplateInstance {
        orderService.order(prescriptionNumber)
        return message.data("message", "Your request has the number: $prescriptionNumber")
    }
}

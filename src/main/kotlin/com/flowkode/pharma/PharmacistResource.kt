package com.flowkode.pharma

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.service.OrderService
import com.flowkode.pharma.util.transactional
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

data class OrderLine(val medication: String, val amount: Long)

data class OrderCard(val id: Long, val code: String, val status: String, val lines: List<OrderLine>)

data class PharmacistView(
    val role: String = PharmacistRole.APPROVALS.name,
    val waiting: Long = 0,
    val card: OrderCard? = null,
    val message: String? = null,
)

@Path("/pharmacist")
@Produces(MediaType.TEXT_HTML)
class PharmacistResource(private val orderService: OrderService) {

    // Template path: templates/PharmacistResource/<name>.html (or .qute.html)
    @CheckedTemplate
    object Templates {

        @JvmStatic
        external fun console(view: PharmacistView): TemplateInstance

        @JvmStatic
        external fun card(view: PharmacistView): TemplateInstance

        @JvmStatic
        external fun counts(view: PharmacistView): TemplateInstance
    }

    @GET
    fun index(@QueryParam("role") role: String?): TemplateInstance {
        val parsed = role(role)
        return Templates.console(PharmacistView(role = parsed.name, waiting = orderService.countWaiting(parsed)))
    }

    @POST
    @Path("take")
    fun take(@QueryParam("role") role: String?): TemplateInstance {
        val parsed = role(role)
        val id = orderService.claimNext(parsed)

        if (id == null)
            return Templates.card(PharmacistView(role = parsed.name, message = "No orders waiting."))

        val card = cardFor(id)
        if (card == null)
            return Templates.card(PharmacistView(role = parsed.name, message = "That order disappeared."))

        return Templates.card(PharmacistView(role = parsed.name, card = card))
    }

    @POST
    @Path("approve/{id}")
    fun approve(@PathParam("id") id: Long): TemplateInstance =
        if (orderService.approve(id)) Templates.card(PharmacistView(message = "Approved ticket $id. Take the next one."))
        else Templates.card(PharmacistView(message = "That order changed. Take the next one."))

    @POST
    @Path("reject/{id}")
    fun reject(@PathParam("id") id: Long): TemplateInstance =
        if (orderService.reject(id)) Templates.card(PharmacistView(message = "Rejected ticket $id. Stock released."))
        else Templates.card(PharmacistView(message = "That order changed. Take the next one."))

    @POST
    @Path("handover/{id}")
    fun handover(@PathParam("id") id: Long): TemplateInstance =
        if (orderService.handover(id)) Templates.card(PharmacistView(message = "Ticket $id handed over. Take the next one."))
        else Templates.card(PharmacistView(message = "That order changed. Take the next one."))

    @GET
    @Path("counts")
    fun counts(@QueryParam("role") role: String?): TemplateInstance {
        val parsed = role(role)
        return Templates.counts(PharmacistView(role = parsed.name, waiting = orderService.countWaiting(parsed)))
    }

    private fun role(raw: String?): PharmacistRole =
        raw?.uppercase()
            ?.let { runCatching { PharmacistRole.valueOf(it) }.getOrNull() }
            ?: PharmacistRole.APPROVALS

    private fun cardFor(id: Long): OrderCard? =
        transactional {
            val order = Order.findById(id) ?: return@transactional null
            OrderCard(
                id = order.id!!,
                code = order.prescriptionCode,
                status = order.status.name,
                lines = order.items.map { OrderLine(it.medication.name, it.amount) },
            )
        }
}

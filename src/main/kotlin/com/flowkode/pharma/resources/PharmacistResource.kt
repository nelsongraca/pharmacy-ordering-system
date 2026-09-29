package com.flowkode.pharma.resources

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PharmacistRole
import com.flowkode.pharma.service.OrderService
import com.flowkode.pharma.util.transactional
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import java.util.UUID

data class OrderLine(val medication: String, val amount: Long)

data class OrderCard(val id: Long, val code: String, val status: String, val lines: List<OrderLine>)

/** A problem order the pharmacist still has to resolve. */
data class Attention(val id: Long, val status: String, val lines: List<OrderLine>)

data class PharmacistView(
    val role: String = PharmacistRole.BOTH.name,
    val who: String = "",
    val waiting: Long = 0,
    val card: OrderCard? = null,
    val message: String? = null,
    val attention: List<Attention> = emptyList(),
    val holding: Boolean = false,
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

        @JvmStatic
        external fun attention(view: PharmacistView): TemplateInstance
    }

    @GET
    fun index(@QueryParam("role") role: String?, @CookieParam("console") console: String?): Response {
        val parsed = role(role)
        val who = console ?: UUID.randomUUID().toString()
        val view = PharmacistView(
            role = parsed.name,
            who = who,
            waiting = orderService.countWaiting(parsed),
            attention = attention(),
            holding = orderService.held(who) != null,
        )
        val response = Response.ok(Templates.console(view))
        // remember the console id so a reload keeps the same held order
        return if (console == null) response.cookie(consoleCookie(who)).build() else response.build()
    }

    @POST
    @Path("take")
    fun take(@QueryParam("role") role: String?, @CookieParam("console") console: String?): TemplateInstance {
        val parsed = role(role)
        val who = who(console)

        // one order at a time: if this console already holds one, show it again instead of taking another
        val held = orderService.held(who)
        val id = held ?: orderService.claimNext(parsed, who)

        if (id == null)
            return Templates.card(PharmacistView(role = parsed.name, who = who, message = "No orders waiting."))

        val card = cardFor(id)
        if (card == null)
            return Templates.card(PharmacistView(role = parsed.name, who = who, message = "That order disappeared."))

        return Templates.card(PharmacistView(role = parsed.name, who = who, card = card, holding = true))
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
    fun handover(@PathParam("id") id: Long, @CookieParam("console") console: String?): TemplateInstance =
        if (orderService.handover(id, who(console))) Templates.card(PharmacistView(message = "Ticket $id handed over. Take the next one."))
        else Templates.card(PharmacistView(message = "That order changed. Take the next one."))

    @POST
    @Path("call/{id}")
    fun call(@PathParam("id") id: Long, @CookieParam("console") console: String?): TemplateInstance {
        val who = who(console)
        // a second "Call patient" is a no-op: show the same card again, no "order changed" scare
        if (!orderService.call(id, who) && orderService.held(who) != id)
            return Templates.card(PharmacistView(message = "That order changed. Take the next one."))

        // keep the ticket on screen so the pharmacist can hand it over after calling
        val card = cardFor(id)
            ?: return Templates.card(PharmacistView(message = "Calling ticket $id."))
        return Templates.card(PharmacistView(card = card, holding = true, message = "Calling ticket $id."))
    }

    @POST
    @Path("dismiss/{id}")
    fun dismiss(@PathParam("id") id: Long): TemplateInstance {
        orderService.dismiss(id)
        return Templates.attention(PharmacistView(attention = attention()))
    }

    @GET
    @Path("attention")
    fun attentionFragment(): TemplateInstance = Templates.attention(PharmacistView(attention = attention()))

    @GET
    @Path("counts")
    fun counts(@QueryParam("role") role: String?): TemplateInstance {
        val parsed = role(role)
        return Templates.counts(PharmacistView(role = parsed.name, waiting = orderService.countWaiting(parsed)))
    }

    private fun role(raw: String?): PharmacistRole =
        raw?.uppercase()
            ?.let { runCatching { PharmacistRole.valueOf(it) }.getOrNull() }
            ?: PharmacistRole.BOTH

    /** The console's id from its cookie; a fresh one if the cookie is missing (e.g. a bare `curl`). */
    private fun who(console: String?): String =
        console?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()

    private fun consoleCookie(who: String): NewCookie =
        NewCookie.Builder("console").value(who).path("/").httpOnly(true).maxAge(60 * 60 * 24).build()

    private fun cardFor(id: Long): OrderCard? =
        transactional {
            val order = Order.findById(id) ?: return@transactional null
            OrderCard(id = order.id!!, code = order.prescriptionCode, status = order.status.name, lines = order.lines())
        }

    private fun attention(): List<Attention> =
        transactional {
            Order.attentionOrders().map { order ->
                Attention(id = order.id!!, status = order.status.name, lines = order.lines())
            }
        }

    /** The order's items as the console renders them. */
    private fun Order.lines(): List<OrderLine> = items.map { OrderLine(it.medication.name, it.amount) }
}

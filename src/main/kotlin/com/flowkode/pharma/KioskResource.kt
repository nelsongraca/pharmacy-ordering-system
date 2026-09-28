package com.flowkode.pharma

import com.flowkode.pharma.service.OrderResult
import com.flowkode.pharma.service.OrderService
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Everything the kiosk template can show. One object keeps every template method's signature identical. */
data class KioskView(
    val error: String? = null,
    val code: String? = null,
    val number: Long? = null,
)

@Path("/")
@Produces(MediaType.TEXT_HTML)
class KioskResource(private val orderService: OrderService) {

    // Template path: templates/KioskResource/kiosk.html (or .qute.html)
    @CheckedTemplate
    object Templates {
        @JvmStatic external fun kiosk(view: KioskView): TemplateInstance        // full page
        @JvmStatic external fun `kiosk$form`(view: KioskView): TemplateInstance   // {#fragment id=form}
        @JvmStatic external fun `kiosk$ticket`(view: KioskView): TemplateInstance // {#fragment id=ticket}
    }

    @GET
    fun index(): TemplateInstance = Templates.kiosk(KioskView())

    /** Called by the ticket card 10 seconds after it appears. */
    @GET
    @Path("kiosk/form")
    fun form(): TemplateInstance = Templates.`kiosk$form`(KioskView())

    /** Always answers 200: htmx does not swap 4xx/5xx responses, so errors are re-rendered forms. */
    @POST
    @Path("orders")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun submit(@FormParam("code") code: String?): TemplateInstance {
        val input = code?.trim().orEmpty()
        return try {
            when (val result = orderService.order(input)) {
                is OrderResult.Placed ->
                    Templates.`kiosk$ticket`(KioskView(number = result.ticket))
                is OrderResult.OutOfStock ->
                    Templates.`kiosk$form`(KioskView(error = OUT_OF_STOCK, code = input))
            }
        } catch (ex: IllegalArgumentException) {
            Templates.`kiosk$form`(KioskView(error = INVALID, code = input))
        }
    }

    companion object {
        private const val OUT_OF_STOCK = "We couldn't prepare that prescription. Please ask at the counter."
        private const val INVALID = "That doesn't look like a prescription number."
    }
}

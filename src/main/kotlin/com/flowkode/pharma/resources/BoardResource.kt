package com.flowkode.pharma.resources

import com.flowkode.pharma.board.BoardTicket
import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.service.StatusStream
import com.flowkode.pharma.util.transactional
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.mutiny.Multi
import io.vertx.core.json.Json
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import org.jboss.resteasy.reactive.RestStreamElementType
import java.time.Duration

data class BoardView(
    val preparing: List<BoardTicket>,
    val ready: List<BoardTicket>,
    val seePharmacist: List<BoardTicket>,
)

@Path("/board")
@Produces(MediaType.TEXT_HTML)
class BoardResource(private val statusStream: StatusStream) {

    // Template path: templates/BoardResource/<name>.html (or .qute.html)
    @CheckedTemplate
    object Templates {
        @JvmStatic external fun board(view: BoardView): TemplateInstance
        @JvmStatic external fun columns(view: BoardView): TemplateInstance
    }

    @GET
    fun index(): TemplateInstance = Templates.board(view())

    /** Initial load: the full three columns. After this the browser keeps up over SSE. */
    @GET
    @Path("columns")
    fun columns(): TemplateInstance = Templates.columns(view())

    /**
     * SSE: one `ticket` event per status change, carrying the ticket's own data (nothing to
     * re-fetch), plus a `ping` heartbeat. A dropped event leaves the board stale until reload.
     */
    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun events(@Context sse: Sse): Multi<OutboundSseEvent> {
        val tickets = statusStream.changes().map { ticket ->
            // SSE `data(Object)` would call toString(); encode the ticket as JSON explicitly.
            sse.newEventBuilder().name("ticket").data(Json.encode(ticket)).build()
        }
        val ping = Multi.createFrom().ticks().every(Duration.ofSeconds(15))
            .map { sse.newEventBuilder().name("ping").data("1").build() }
        return Multi.createBy().merging().streams(tickets, ping)
    }

    private fun view(): BoardView {
        val rows = transactional { Order.boardOrders().map { BoardTicket.forOrder(it) } }
        return BoardView(
            preparing = rows.filter { it.column == "preparing" }.sortedWith(BOARD_ORDER),
            ready = rows.filter { it.column == "ready" }.sortedWith(BOARD_ORDER),
            seePharmacist = rows.filter { it.column == "seePharmacist" }.sortedWith(BOARD_ORDER),
        )
    }

    companion object {
        /** Called tickets first, then oldest first. Mirrors `compareTickets` in app.js. */
        private val BOARD_ORDER: Comparator<BoardTicket> =
            compareByDescending<BoardTicket> { it.calling }.thenBy { it.sortKey }.thenBy { it.id }
    }
}

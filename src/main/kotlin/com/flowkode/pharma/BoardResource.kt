package com.flowkode.pharma

import com.flowkode.pharma.domain.Order
import com.flowkode.pharma.domain.PrescriptionStatus
import com.flowkode.pharma.service.StatusStream
import com.flowkode.pharma.util.transactional
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.mutiny.Multi
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import org.jboss.resteasy.reactive.RestStreamElementType
import java.time.Duration
import java.time.Instant

data class BoardTicket(val id: Long, val stage: String, val delayed: Boolean, val calling: Boolean = false)

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

    @GET
    @Path("columns")
    fun columns(): TemplateInstance = Templates.columns(view())

    /** SSE: a `changed` event per status change plus a `ping` heartbeat. The browser just re-fetches /board/columns. */
    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    fun events(@Context sse: Sse): Multi<OutboundSseEvent> {
        val changed = statusStream.changes().map { sse.newEventBuilder().name("changed").data("1").build() }
        val ping = Multi.createFrom().ticks().every(Duration.ofSeconds(15))
            .map { sse.newEventBuilder().name("ping").data("1").build() }
        return Multi.createBy().merging().streams(changed, ping)
    }

    private fun view(): BoardView {
        val now = Instant.now()
        val delay = DELAYED_AFTER
        val rows = transactional { Order.boardOrders().map { Ticket(it.id!!, it.status, it.statusChangedAt, it.calledAt) } }

        fun ticket(row: Ticket, delayed: Boolean = false) = BoardTicket(row.id, stage(row.status), delayed)
        fun calling(row: Ticket) = row.calledAt?.let { Duration.between(it, now) < CALLED_HIGHLIGHT } ?: false

        return BoardView(
            preparing = rows.filter { it.status in PREPARING }
                .map { ticket(it, Duration.between(it.changedAt, now) > delay) },
            ready = rows.filter { it.status == PrescriptionStatus.READY }
                .map { BoardTicket(it.id, stage(it.status), false, calling(it)) },
            seePharmacist = rows.filter { it.status in PROBLEMS }.map { ticket(it) },
        )
    }

    private fun stage(status: PrescriptionStatus): String = when (status) {
        PrescriptionStatus.AWAITING_APPROVAL, PrescriptionStatus.IN_REVIEW -> "Pharmacist review"
        PrescriptionStatus.PACKAGING -> "Packing"
        PrescriptionStatus.READY -> "Ready"
        PrescriptionStatus.OUT_OF_STOCK -> "Out of stock"
        PrescriptionStatus.REJECTED -> "Rejected"
        PrescriptionStatus.FAILED -> "Failed"
        else -> status.name
    }

    private data class Ticket(val id: Long, val status: PrescriptionStatus, val changedAt: Instant, val calledAt: Instant?)

    companion object {
        //todo: make configurable (pharmacy.board.delayed-after) once we want per-env tuning; a constant is enough for the demo
        private val DELAYED_AFTER: Duration = Duration.ofMinutes(3)

        /** A ticket called within this window is highlighted as "Now calling". */
        private val CALLED_HIGHLIGHT: Duration = Duration.ofSeconds(60)

        private val PREPARING = setOf(
            PrescriptionStatus.AWAITING_APPROVAL,
            PrescriptionStatus.IN_REVIEW,
            PrescriptionStatus.PACKAGING,
        )
        private val PROBLEMS = setOf(
            PrescriptionStatus.OUT_OF_STOCK,
            PrescriptionStatus.REJECTED,
            PrescriptionStatus.FAILED,
        )
    }
}

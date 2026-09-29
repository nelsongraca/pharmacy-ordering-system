# Design

Prescription fulfillment for a pharmacy: kiosk intake, pharmacist approval, simulated packaging, delivery call-out, and a live wall board. Kotlin + Quarkus, PostgreSQL, RabbitMQ.

The point of this document is the "why": what each component is, how they coordinate, where failures surface, and the assumptions behind them.

## System at a glance

```
patient ──/kiosk──▶ KioskResource ──▶ OrderService.create ──▶ Postgres (orders, medications, order_items)
                                        │
                                        └─ after commit: publish status event
pharmacist ──/pharmacist──▶ PharmacistResource ──▶ OrderService (claim/approve/reject/call/handover/dismiss)
                                        │
                                        └─ approve: publish ──▶ RabbitMQ orders.packaging
                                                                    │
                                              PackagerService (random delay, then PACKAGING→READY)
                                                                    │ throw (demo)
                                                                    ▼
                                                        orders.dlx ─▶ orders.packaging.dlq
                                                                    │
                                                        DeadLetterService (PACKAGING→FAILED, release stock)

any status change ──▶ RabbitMQ orders.status (fanout) ──▶ per-instance queue ──▶ StatusStream ──▶ SSE /board/events
wall screen ──/board──▶ BoardResource reads Postgres (source of truth)
```

The **database is the source of truth**. The packaging message is tiny (`{"orderId":123}`) and only says "pack order X"; the packager reloads from the DB. Status messages carry the whole `BoardTicket` (id, stage, calling, column, sort key) so the board never re-reads the DB to render a change. Keeping the work message small makes retries and duplicates harmless.

## Components

- **Intake** (`KioskResource`, `OrderService.create`): decode the number, reserve every item atomically, insert the order, or record `OUT_OF_STOCK`. Synchronous: the kiosk gets a ticket number back
  immediately. Signals malformed input, duplicate active prescription, and source outage with plain-language messages (section "Failure handling").
- **Pharmacist console** (`PharmacistResource`, `OrderService`): role-based "take next" plus approve / reject / call / hand over / resolve. Server-rendered Qute + htmx, no SPA, no login.
- **Packager** (`PackagerService`): `@Incoming` on `orders.packaging`; sleeps a random 3–8s outside any transaction, then `PACKAGING → READY`. The demo code throws. (The connector consumer is disabled in tests, which call `consume` directly; the delay is not overridden.)
- **Dead-letter handler** (`DeadLetterService`): `@Incoming` on `orders.packaging.dlq`; `PACKAGING → FAILED` and release stock.
- **Packaging publisher** (`PackagingPublisher`): the only thing that touches the packaging work queue. `OrderService.approve` and `Republisher` both call it to send the work message and record
  `last_published_at`.
- **Republisher** (`Republisher`): `@Scheduled(every = "30s")`; re-sends the packaging message for `PACKAGING` orders that were not published recently, recovering a publish lost between commit and
  broker (see "Recovering a lost publish"). Disabled in `%test`.
- **Status stream** (`StatusStream`): consumes this instance's own queue and forwards the ticket; `BoardResource`'s `GET /board/events` turns it into a `ticket` SSE event.
- **Board** (`BoardResource`): renders the three columns once on `GET /board/columns`; after that the browser mutates the DOM from `ticket` events, so no board re-fetches because something changed.
  Columns are ordered oldest status change first; in Ready, called tickets sort above the rest (longest-waiting-called first), and a called ticket stays highlighted until it is handed over.

## Stock model

Availability is derived, not stored as a single number:

- `medications.stock` - physical units on hand.
- `medications.reserved` - units held for active orders. Invariant: `0 ≤ reserved ≤ stock`.

Each effect is one conditional SQL statement, so it is race-safe:

- Reserve: `reserved = reserved + n WHERE id = ? AND stock - reserved >= n`. Zero rows means shortage.
- Release: `reserved = reserved - n`, guarded by the order's `stock_released` flag so it happens at most once.
- Consume (hand-over): `stock = stock - n, reserved = reserved - n`.

Reservation is **all-or-nothing** and processes items in medication-id order (deadlock avoidance). On shortage the reservation transaction rolls back, then a separate transaction records the
`OUT_OF_STOCK` order.

## Order lifecycle

Statuses: `OUT_OF_STOCK`, `AWAITING_APPROVAL`, `IN_REVIEW`, `REJECTED`, `PACKAGING`, `READY`, `COMPLETED`, `FAILED`. Terminal: `COMPLETED`, `REJECTED`, `OUT_OF_STOCK`, `FAILED`.

**Every transition is a conditional update** (`WHERE id = ? AND status = ?expected`). If it affects 0 rows it is a no-op, which makes at-least-once delivery safe (a duplicate packaging message or a
replayed DLQ message cannot move an order twice).

| From                | To                  | Trigger                          | Effects                                                            |
|---------------------|---------------------|----------------------------------|--------------------------------------------------------------------|
| -                   | `AWAITING_APPROVAL` | Kiosk submit, all items reserved | Insert order + items, reserve stock; status event                  |
| -                   | `OUT_OF_STOCK`      | Any item unable to reserve       | Nothing stays reserved; status event                               |
| `AWAITING_APPROVAL` | `IN_REVIEW`         | "Take next" (approvals)          | Write lock; set `claimed_by`/`claimed_at`                          |
| `IN_REVIEW`         | `PACKAGING`         | Approve                          | Clear claim; publish `orders.packaging` + status event             |
| `IN_REVIEW`         | `REJECTED`          | Reject                           | Clear claim; release stock once                                    |
| `PACKAGING`         | `READY`             | Packager done                    | Status event                                                       |
| `PACKAGING`         | `FAILED`            | DLQ handler                      | Release stock once; status event                                   |
| `READY`             | `READY` (claimed)   | "Take next" (deliveries)         | Set `claimed_by`/`claimed_at`; status stays `READY`                |
| `READY`             | `READY` (called)    | "Call patient" (held order)      | Set `called_at`; status event; board highlights it until hand-over |
| `READY`             | `COMPLETED`         | "Handed over" (held order)       | Clear claim; consume stock                                         |
| problem             | dismissed           | "Resolved"                       | Set `dismissed_at`; board hides it                                 |

`status_changed_at` is set on every change (also inside the bulk updates - JPQL bypasses `@UpdateTimestamp`). `called_at`/`dismissed_at` are set at most once by their own conditional updates. A
non-null `claimed_by`/`claimed_at` marks an order as held by a console; it is cleared on the next transition.

## Coordination and concurrency

- **Race for the last box:** the atomic reserve statement; exactly one order wins, the rest become `OUT_OF_STOCK`.
- **Two pharmacists, one order:** "take next" only selects orders with `claimed_by IS NULL`, under a `PESSIMISTIC_WRITE` lock. The claim records *who* holds it, so a second "Take next" (from any
  console) skips an order already held.
- **One order at a time per console:** a console (identified by an opaque `console` cookie) holding an order is given that same order back instead of a new one, and its console disables "Take next".
  Two consoles each hold their own order independently.
- **Needs attention is desk-wide, unclaimed.** `OUT_OF_STOCK`, `REJECTED`, and `FAILED` orders that are not dismissed show on every console, and "Resolved" dismisses the order globally — the first
  pharmacist to click it clears it for everyone. This is intentional for a shared counter: the fact ("ticket 42 was rejected") concerns the whole desk, not one console. It is *not* per-console like
  held work; claimable attention items are a possible improvement if double-handling ever matters.
- **Stale claims:** none released automatically. If a claim is abandoned, the order stays held; a timeout/sweeper over `claimed_at` is a next step.
- **Duplicate delivery:** conditional transitions make replays no-ops.
- **Lost/duplicate messages:** the DB is the truth; a lost status event leaves the board stale until reload, and a lost packaging message is re-sent by the republisher (below).
- **Stock leaks:** release/consume are guarded, so a rejected or failed order releases exactly once.

## Recovering a lost publish

Approve commits the DB transition to `PACKAGING` and then publishes the work message: two steps that cannot be atomic. A crash (or broker error) in between leaves an order in `PACKAGING` that the
packager will never hear about — the ticket silently stalls on the board.

`Republisher` (`@Scheduled(every = "30s")`) closes that gap:

1. Find orders still in `PACKAGING` that were not published in the last 30 seconds — `Order.stalled(cutoff)` matches `lastPublishedAt is null or lastPublishedAt < cutoff`.
2. Re-send the work message through `PackagingPublisher` for each (which also refreshes `last_published_at`).

Properties that make it safe:

- **Duplicates are harmless.** The packager only acts on orders still in `PACKAGING` and its transition is conditional, so a second delivery is a no-op. Re-sending an order the packager already
  processed changes nothing.
- **The DB is the scheduler's source of truth.** The republisher reads committed state only; it never needs the original transaction.
- **Only `PACKAGING` needs this.** `AWAITING_APPROVAL` is picked up by a pharmacist on demand, and `READY` delivery is claimed straight from the database, so neither can be "lost" the same way.

Limits: this is best-effort at-least-once recovery, not exactly-once. It does not cover a lost status event (the board is stale until reload) or a multi-instance race, and the 30s window means a
genuinely lost message stalls for up to ~30s. A transactional outbox is the production-grade answer (see Future work).

## Messaging topology

| Object                    | Type                                 | Purpose                                                    |
|---------------------------|--------------------------------------|------------------------------------------------------------|
| `orders.packaging`        | durable queue                        | Work queue for the packager; rejected messages dead-letter |
| `orders.dlx`              | direct exchange                      | Routes `packaging.failed`                                  |
| `orders.packaging.dlq`    | durable queue                        | Terminal failures, consumed by the DLQ handler             |
| `orders.status`           | durable fanout exchange              | Every status change; each instance gets its own copy       |
| per-instance status queue | exclusive, auto-delete, server-named | Bound to `orders.status`, feeds SSE                        |

Why this shape:

- **Packaging is a real work queue.** One instance should pack each order once, and a poison message must not be retried forever - hence a queue with dead-lettering.
- **Approval is not a queue.** Multiple pharmacists each take one order from a shared pool; ownership is a short DB transaction (a write lock), not a long-held unacked message. That is why there is no
  `orders.approval` or `orders.delivery` queue and no `basic.get`.
- **Status is a fanout.** The board must update on any instance that handles a change. Each instance consumes its own ephemeral queue, so every instance sees every event and pushes it to that
  instance's browsers over SSE. (One instance is enough for the demo; the shape is what makes it safe to run two.)

`/board/events` carries the ticket, not a signal to reload: each status change becomes a `ticket` event whose payload is the ticket's own data, and the browser places it in the right column. A dropped
event leaves one screen stale until it is reloaded; a resync poll is deliberately omitted (see Future work).

**Why the 15s `ping`.** With no resync poll, the heartbeat is the only traffic on an idle stream - and that is exactly its job. Board screens are long-lived connections that can sit for hours with
nothing to say. Intermediaries (reverse proxies, load balancers, NAT) close idle TCP connections, and neither side notices until the next real event, which is then lost. The periodic `ping` keeps the
connection warm and gives the client a liveness signal, so a dead connection surfaces immediately instead of on the next status change. It also means an idle-but-healthy stream is never mistaken for a
broken one.

## Failure handling

| Failure                       | Behavior                                                      | Surface                        |
|-------------------------------|---------------------------------------------------------------|--------------------------------|
| Malformed code                | Rejected before any write                                     | Kiosk message                  |
| Prescription source down      | Logged, nothing created                                       | Kiosk "Something went wrong…"  |
| Duplicate active prescription | Best-effort check                                             | Kiosk "already being prepared" |
| Out of stock                  | `OUT_OF_STOCK` order, all-or-nothing                          | Board "See pharmacist"         |
| Last-box race                 | Atomic reserve; one winner                                    | Board                          |
| Reject / packager failure     | Release stock once, same transaction as the status change     | Board                          |
| Packager throws               | Rejected → `orders.dlx` → `orders.packaging.dlq` → `FAILED`   | Board "See pharmacist"; log    |
| Duplicate message             | Conditional transition no-op                                  | -                              |
| Publish fails after commit    | Logged; the republisher re-sends within ~30s                       | Log only                       |
| SSE drops                     | Browser reconnects; a missed change is stale until reload     | Board recovers on reload       |

## Technology decisions

- **Quarkus + Kotlin + Hibernate ORM Panache (Kotlin), blocking.** Small, bootable in one command, and the Active Record style keeps the state changes in the entities.
- **Qute + htmx 2 + Pico.css**, bundled by `quarkus-web-bundler`. Server-rendered fragments, no JS build step; SSE pushes ticket changes, and the browser mutates the board from them (a reload is the
  fallback), this was done to avoid extra work with frameworks that could require a bigger build. Ideally frontend should be a separate project so it can be handled by different teams if needed.
- **RabbitMQ via `quarkus-messaging-rabbitmq` (SmallRye).** Mandated, and a good fit for the packaging work queue plus the status fanout.
- **Postgres via Dev Services, schema by Hibernate, seed in `import.sql`.**
- **Kotlin `kotlinx`-free; no coroutines.** Mocking a delayed packager with `Thread.sleep` on a worker thread is simpler than a reactive pipeline here.

## Assumptions

- **The prescription source is external.** It sits behind a client; the shipped implementation is a fake that decodes the number locally (six digits, one per medication id).
- **The ticket number is the order id**; it is what patients see and pharmacists call out. The board shows ticket numbers only, never names or medications.
- **Orders are all-or-nothing, and intake is synchronous.**
- **Several pharmacists work at once.** Each console takes one order at a time; a console is identified by an opaque `console` cookie (set on first visit), so two consoles hold their own orders independently. There is no login. The "Needs attention" list is shared by the desk: resolving an order clears it for everyone.
- **Duplicate-submit guard is best-effort**, an application check rather than a DB partial unique index (Hibernate schema generation cannot express one).
- **No auth.** Claims are scoped to an opaque `console` cookie, not a verified user: an order is held by "some console".

## Future work

1. **Transactional outbox** to replace the republisher's best-effort recovery and to cover status-event loss and multi-instance races.
2. **Stale-claim recovery** - release or requeue an abandoned order (timeout over `claimed_at`).
3. **Broker-level packaging retry** - add a retry mechanism instead of failing immediately on `PACKAGING` error, this would survive transient errors.
4. **Flyway/Liquibase** - For proper database migration control instead of relying on hibernate schema generation.
5. **Configuration** - Instead of constants for timings rely on `@ConfigMapping` to add configuration
6. **Real identity/auth** for the pharmacist console, so claims are attributable and per-user, this implementation has no security.
7. **Board staleness** - an optional resync (periodic poll or reconnect refetch) and a "taking longer than usual" indicator for tickets stuck in Preparing. Not considered in order to keep the board
   push-only, if there is evidence either from QA or production that we really need this then it should be planned and implemented.
8. **`order_events` audit table** - an immutable log of status changes, instead of only the current row.
9. **Claimable attention items** - the "Needs attention" list is desk-wide today; per-console claims would prevent two pharmacists resolving the same problem. Only needed if that shows up in practice.

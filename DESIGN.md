# Design

Prescription fulfillment for a pharmacy: kiosk intake, pharmacist approval, simulated packaging, delivery call-out, and a live wall board. Kotlin + Quarkus, PostgreSQL, RabbitMQ.

The original challenge is [`base-spec.md`](base-spec.md) — the source of truth. `spec.md` is a derived build spec; where the two differ, `base-spec.md` wins, and the differences are listed under Deviations below.

The point of this document is the "why": what each component is, how they coordinate, where failures surface, and where we knowingly deviated from the spec.

## System at a glance

```
patient ──/──▶ KioskResource ──▶ OrderService.create ──▶ Postgres (orders, medications, order_items)
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

The **database is the source of truth**. Messages are tiny (`{"orderId":123}`) and only say "look at order X"; consumers reload from the DB. That keeps retries and duplicates harmless.

## Components

- **Intake** (`KioskResource`, `OrderService.create`): decode the number, reserve every item atomically, insert the order, or record `OUT_OF_STOCK`. Synchronous: the kiosk gets a ticket number back immediately. Signals malformed input, duplicate active prescription, and source outage with plain-language messages (section "Failure handling").
- **Pharmacist console** (`PharmacistResource`, `OrderService`): role-based "take next" plus approve / reject / call / hand over / resolve. Server-rendered Qute + htmx, no SPA, no login.
- **Packager** (`PackagerService`): `@Incoming` on `orders.packaging`; sleeps a random 3–8s (0 in tests) outside any transaction, then `PACKAGING → READY`. The demo code throws.
- **Dead-letter handler** (`DeadLetterService`): `@Incoming` on `orders.packaging.dlq`; `PACKAGING → FAILED` and release stock.
- **Status publisher** (`StatusPublisher`): one method used by every transition to publish to `orders.status` after commit.
- **Status stream** (`StatusStream`): consumes this instance's own queue and broadcasts; `BoardResource`'s `GET /board/events` turns that into SSE (`changed` events + a 15s `ping`).
- **Board** (`BoardResource`): reads the DB and renders three columns; the browser re-fetches `/board/columns` on any SSE event and every 30s.

## Stock model

Availability is derived, not stored as a single number:

- `medications.stock` — physical units on hand.
- `medications.reserved` — units held for active orders. Invariant: `0 ≤ reserved ≤ stock`.

Each effect is one conditional SQL statement, so it is race-safe:

- Reserve: `reserved = reserved + n WHERE id = ? AND stock - reserved >= n`. Zero rows means shortage.
- Release: `reserved = reserved - n`, guarded by the order's `stock_released` flag so it happens at most once.
- Consume (hand-over): `stock = stock - n, reserved = reserved - n`.

Reservation is **all-or-nothing** and processes items in medication-id order (deadlock avoidance). On shortage the reservation transaction rolls back, then a separate transaction records the `OUT_OF_STOCK` order.

## Order lifecycle

Statuses: `OUT_OF_STOCK`, `AWAITING_APPROVAL`, `IN_REVIEW`, `REJECTED`, `PACKAGING`, `READY`, `COMPLETED`, `FAILED`. Terminal: `COMPLETED`, `REJECTED`, `OUT_OF_STOCK`, `FAILED`.

**Every transition is a conditional update** (`WHERE id = ? AND status = ?expected`). If it affects 0 rows it is a no-op, which makes at-least-once delivery safe (a duplicate packaging message or a replayed DLQ message cannot move an order twice).

| From | To | Trigger | Effects |
|---|---|---|---|
| — | `AWAITING_APPROVAL` | Kiosk submit, all items reserved | Insert order + items, reserve stock; status event |
| — | `OUT_OF_STOCK` | Any item unable to reserve | Nothing stays reserved; status event |
| `AWAITING_APPROVAL` | `IN_REVIEW` | "Take next" (approvals) | Write lock; set `claimed_by`/`claimed_at` |
| `IN_REVIEW` | `PACKAGING` | Approve | Clear claim; publish `orders.packaging` + status event |
| `IN_REVIEW` | `REJECTED` | Reject | Clear claim; release stock once |
| `PACKAGING` | `READY` | Packager done | Status event |
| `PACKAGING` | `FAILED` | DLQ handler | Release stock once; status event |
| `READY` | `READY` (claimed) | "Take next" (deliveries) | Set `claimed_by`/`claimed_at`; status stays `READY` |
| `READY` | `READY` (called) | "Call patient" (holder) | Set `called_at`; status event |
| `READY` | `COMPLETED` | "Handed over" (holder) | Clear claim; consume stock |
| problem | dismissed | "Resolved" | Set `dismissed_at`; board hides it |

`status_changed_at` is set on every change (also inside the bulk updates — JPQL bypasses `@UpdateTimestamp`). `called_at`/`dismissed_at` are set at most once by their own conditional updates. `claimed_by`/`claimed_at` record who holds an order; they are cleared on the next transition.

## Coordination and concurrency

- **Race for the last box:** the atomic reserve statement; exactly one order wins, the rest become `OUT_OF_STOCK`.
- **Two pharmacists, one order:** "take next" only selects orders with `claimed_by IS NULL`, under a `PESSIMISTIC_WRITE` lock. The claim is then persisted, so a second "Take next" skips an order already held.
- **One order per pharmacist:** a pharmacist holding an order is given that same order back instead of a new one, and the console disables "Take next".
- **Stale claims:** none released automatically. If a pharmacist abandons a claim, the order stays held; a timeout/sweeper over `claimed_at` is a next step.
- **Duplicate delivery:** conditional transitions make replays no-ops.
- **Lost/duplicate messages:** the DB is the truth; a lost status event only means the board waits for its next poll, and a lost packaging message is re-sent by the republisher (below).
- **Stock leaks:** release/consume are guarded, so a rejected or failed order releases exactly once.

## Recovering a lost publish

The packaging message is published after the DB commit, so a crash in between could leave an order stuck in `PACKAGING` with no packager message. `Republisher` runs every 30s, finds `PACKAGING` orders whose `last_published_at` is older than the threshold, and re-sends the packaging message. Duplicates are harmless (the packager skips anything not in `PACKAGING`). This is enough for a single instance; a transactional outbox would be the production-grade answer.

## Messaging topology

| Object | Type | Purpose |
|---|---|---|
| `orders.packaging` | durable queue | Work queue for the packager; rejected messages dead-letter |
| `orders.dlx` | direct exchange | Routes `packaging.failed` |
| `orders.packaging.dlq` | durable queue | Terminal failures, consumed by the DLQ handler |
| `orders.status` | durable fanout exchange | Every status change; each instance gets its own copy |
| per-instance status queue | exclusive, auto-delete, server-named | Bound to `orders.status`, feeds SSE |

Why this shape:

- **Packaging is a real work queue.** One instance should pack each order once, and a poison message must not be retried forever — hence a queue with dead-lettering.
- **Approval is not a queue.** Multiple pharmacists each take one order from a shared pool; ownership is a short DB transaction (a write lock), not a long-held unacked message. That is why there is no `orders.approval` or `orders.delivery` queue and no `basic.get`.
- **Status is a fanout.** The board must update on any instance that handles a change. Each instance consumes its own ephemeral queue, so every instance sees every event and pushes it to that instance's browsers over SSE. (One instance is enough for the demo; the shape is what makes it safe to run two.)

`/board/events` carries no HTML: the event name is `changed` and the payload is a dummy. The browser re-fetches `/board/columns`, so the board also recovers from a dropped connection or a missed event via its 30-second poll.

## Failure handling

| Failure | Behavior | Surface |
|---|---|---|
| Malformed code | Rejected before any write | Kiosk message |
| Prescription source down | Logged, nothing created | Kiosk "Something went wrong…" |
| Duplicate active prescription | Best-effort check | Kiosk "already being prepared" |
| Out of stock | `OUT_OF_STOCK` order, all-or-nothing | Board "See pharmacist" |
| Last-box race | Atomic reserve; one winner | Board |
| Reject / packager failure | Release stock once, same transaction as the status change | Board |
| Packager throws | Rejected → `orders.dlq` → `FAILED` | Board "See pharmacist"; log |
| Duplicate message | Conditional transition no-op | — |
| Publish fails after commit | Logged; order stalls and shows as delayed | Board "taking longer than usual" |
| SSE drops | Reconnect + 30s poll | Board recovers |

## Technology decisions

- **Quarkus + Kotlin + Hibernate ORM Panache (Kotlin), blocking.** Small, bootable in one command, and the Active Record style keeps the state changes in the entities.
- **Qute + htmx 2 + Pico.css**, bundled by `quarkus-web-bundler`. Server-rendered fragments, no JS build step; SSE gives live updates; plain polling is the fallback.
- **RabbitMQ via `quarkus-messaging-rabbitmq` (SmallRye).** Mandated, and a good fit for the packaging work queue plus the status fanout.
- **Postgres via Dev Services, schema by Hibernate, seed in `import.sql`.** See Deviations.
- **Kotlin `kotlinx`-free; no coroutines.** Mocking a delayed packager with `Thread.sleep` on a worker thread is simpler than a reactive pipeline here.

## Deviations from the spec

- **Packager retry limit.** The spec asked for `x-delivery-limit = 3` before the DLQ. The SmallRye connector has no generic queue-arguments setting, so we use its native DLQ (`auto-bind-dlq`) and fail fast: a rejected message goes straight to `orders.packaging.dlq`. Verified in dev mode (`P-00009` → `FAILED`).
- **Classic queues, not quorum.** Not needed for the demo; production would use quorum queues for durability/HA.
- **No `claimed_by` / `claimed_at`.** A delivery claim is an in-flight pessimistic lock only. Consequence: once the claim transaction commits, the order is still `READY` and another "take next" can return it. `claimed_by` is the fix (see Next steps).
- **Duplicate-submit guard is best-effort**, an application check, not a DB partial unique index (Hibernate schema generation cannot express one).
- **No Flyway.** `import.sql` + Hibernate schema for a five-hour challenge; migration tooling is a "next step".
- **Prescription format is local decode**, `P-#####` (one digit per medication id, plus an optional sixth for the favorite), not a seeded lookup keyed by `RX-####`. Demo hooks are `P-60000` (out of stock), `P-00009` (packager failure), `P-99999` (source outage).
- **The board shows ticket numbers, not patient names.** `base-spec.md` says the pharmacist calls out the patient's number/name; we call the ticket number so no personal data is ever displayed. A conscious privacy choice, not a requirement of the challenge.
- **The favorite medication is included.** `base-spec.md` invites adding one; the seed and the sixth decode digit make `Methylphenidate` orderable (`P-000001`).
- **No pharmacist session/cookies.** The role and the pharmacist identity travel as `?role=`/`?who=` query parameters; there is no auth, so `claimed_by` is a name, not a verified user.
- **Lost publish recovery is a scheduled republisher** (`last_published_at`, packaging only), not a transactional outbox.
- **Live board is an addition** beyond the base challenge: the base asks for a synchronous way for patients to track status, which the board plus SSE provides.

## Next steps

Ordered by value:

1. **Transactional outbox** to replace the republisher's best-effort recovery and to cover status-event loss and multi-instance races.
2. **Stale-claim recovery** — release or requeue an order held by a pharmacist who abandoned it (timeout over `claimed_at`).
3. **Broker-level packaging retry** — quorum queues + `x-delivery-limit`, or app-level attempts, to survive transient packager errors instead of failing immediately.
4. **Flyway migrations** and an `order_events` audit table.
5. **Configurable timings** (`pharmacy.board.delayed-after`, `pharmacy.packager.delay-*`, `pharmacy.republish-after`) via `@ConfigMapping` instead of constants.
6. **Real identity/auth** for the pharmacist console, so `claimed_by` is trustworthy and per-user.

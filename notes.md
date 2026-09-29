Active Record pattern for simplicity

## Claims
`Order.claimedBy`/`claimedAt` make a pickup durable: "Take next" only takes orders with
`claimedBy is null`, so it never hands out an order someone already holds. A pharmacist holding
an order gets it back instead of a second one (one at a time), and the console disables "Take
next" while holding. Transitions clear the claim; delivery hand-over/call require the holder
(`claimedBy = :me`). Delivery still keeps the status `READY` while claimed.

## Future improvements
- Make timing configurable again: `BoardResource.DELAYED_AFTER`, the packager sleep constants,
  and `Republisher.REPUBLISH_AFTER_MS` should be `@ConfigMapping` properties
  (`pharmacy.board.delayed-after`, `pharmacy.packager.delay-min/max`, `pharmacy.republish-after`).
  Statics are fine for the demo, wrong for per-env tuning.
- Flyway migrations instead of `import.sql` + Hibernate drop-and-create (spec §6) — required
  before any real deployment.
- `order_events` audit table (spec §6, optional).
- Full transactional outbox. The republisher covers the realistic case (packaging message lost
  between commit and publish), but not a multi-instance race or status-event loss.
- Stale-claim recovery: if a pharmacist crashes while holding an order, nothing releases the
  claim today (the console would need a "release"/timeout). A sweeper over `claimedAt` would do it.

## Deviations to record in DESIGN.md
- spec §7 originally asked for `x-delivery-limit=3` so the packager would retry 3 times before
  the DLQ. The SmallRye RabbitMQ connector has no generic queue-arguments setting, so we use the
  connector's native DLQ (`auto-bind-dlq`) and fail fast: a rejected packaging message goes
  straight to `orders.packaging.dlq` (verified in dev). Retry could be added later with the
  Quarkiverse client or app-level attempts.
- Queues are classic (connector default), not quorum: not needed for the demo; production would
  use quorum queues.
- Duplicate active prescription is a best-effort application check, not a DB partial unique index
  (Hibernate schema generation cannot express one).
- Claims are durable (`claimedBy`/`claimedAt`) but there is no identity/auth: the pharmacist is a
  `who` query parameter, defaulting to `pharmacist`, not a logged-in user.
- Publish-loss recovery is a 30s `@Scheduled` republisher over `lastPublishedAt` (packaging only),
  not a transactional outbox.

Active Record pattern for simplicity

## To consider: claimed_by / claimed_at on orders
Delivery claim keeps the order READY, so a claimed order is indistinguishable from an
unclaimed one. The pessimistic write lock only prevents a *simultaneous* double-claim;
once the claim transaction commits the row is still READY, so another "Take next" can
hand the same ticket out again.

Adding `claimed_by` (and `claimed_at`, cleared on the next transition) would make claims
durable and unlock:
- "a pharmacist holding an order can't take another" (server returns the current one)
- `Take next` skipping already-claimed READY orders
- a "Now calling" board state via `called_at`

Spec §5 rows 8-10, §8. We deliberately deferred this; not a status change.

## Future improvements
- Make timing configurable again: `BoardResource.DELAYED_AFTER` and the packager sleep
  constants should be `@ConfigMapping` properties (`pharmacy.board.delayed-after`,
  `pharmacy.packager.delay-min/max`). Statics are fine for the demo, wrong for per-env tuning.
- Flyway migrations instead of `import.sql` + Hibernate drop-and-create (spec §6) — required
  before any real deployment.
- `order_events` audit table (spec §6, optional).

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
- No `claimed_by`/`claimed_at`; a delivery claim is only an in-flight pessimistic lock.

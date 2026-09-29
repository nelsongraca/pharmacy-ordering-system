# Pharmacy Ordering System

A prescription fulfillment demo for a pharmacy. A patient enters a prescription number at a
kiosk, gets a ticket number, and watches a live wall board while the order is checked against
stock, approved by a pharmacist, packed, and finally called out and handed over.

Kotlin + Quarkus, PostgreSQL, RabbitMQ. See [DESIGN.md](DESIGN.md) for the design, decisions
and known limitations.

## Requirements

- **JDK 21**
- **Docker** (Quarkus Dev Services start PostgreSQL and RabbitMQ automatically)

No other setup: no database to install, no broker to run.

## Run

```
./mvnw quarkus:dev
```

Then open:

- Kiosk (patients): <http://localhost:8080/>
- Board (wall screen): <http://localhost:8080/board>
- Pharmacist console: <http://localhost:8080/pharmacist>

## Two-minute demo

Prescription numbers are decoded locally: `P-` followed by five digits, where position *i* is
the quantity of medication id *i* — 1 Amoxicillin, 2 Ibuprofen, 3 Lisinopril, 4 Metformin,
5 Atorvastatin. Zero means "none". An optional sixth digit addresses the added favorite
medication, 6 Methylphenidate.

1. **Kiosk** → `P-10000` → a ticket appears; the board shows it under **Preparing**. The form
   resets itself after 10 seconds.
2. **Pharmacist**, role *Approvals* → **Take next** → **Approve**. The ticket moves to **Ready**
   after the packager finishes (3–8 seconds).
3. **Pharmacist**, role *Deliveries* → **Take next** → **Call patient**. The board highlights
   **Now calling**; then **Handed over** completes the order.
4. Open **/board** in two windows to see live updates, and reload one to show that state
   recovers.
5. **Failure hooks:**
   - `P-60000` → out of stock: the ticket goes to the board's **See pharmacist** column.
   - `P-99999` → prescription source outage: the kiosk shows an error and creates nothing.
   - `P-00009` → the packager fails: after approval the order ends in **Failed** (routed
     through the dead-letter queue) and its reserved stock is released.
6. `P-000001` (six digits) → one Methylphenidate, the added favorite medication.

## Tests

```
./mvnw test
```

Docker must be running: the tests use Dev Services for PostgreSQL and RabbitMQ.

## Assumptions

- The prescription source is external and sits behind a client; the shipped implementation is a
  fake that decodes the number locally.
- Several pharmacists can work at once; each takes one order at a time and picks a role
  (approvals, deliveries, or both). There is no login.
- The ticket number is the order id; it is what patients see and pharmacists call out.
- The board shows ticket numbers only — never names or medications.
- Orders are all-or-nothing, and intake is synchronous.

## Code map

| Path | What it does |
|---|---|
| `KioskResource.kt`, `templates/KioskResource/` | Patient intake form and ticket |
| `BoardResource.kt`, `templates/BoardResource/` | Live board, columns fragment, SSE |
| `PharmacistResource.kt`, `templates/PharmacistResource/` | Pharmacist console and actions |
| `service/OrderService.kt` | Intake, claims, and all status transitions |
| `service/PackagerService.kt` | Simulated packaging consumer |
| `service/DeadLetterService.kt` | Marks dead-lettered orders `FAILED` and releases stock |
| `service/StatusPublisher.kt`, `service/StatusStream.kt` | Status events and the SSE broadcast |
| `service/PrescriptionService.kt`, `service/StockService.kt` | Prescription decode; stock effects |
| `domain/` | `Order`, `OrderItem`, `Medication`, `PrescriptionStatus` |
| `resources/application.yaml` | RabbitMQ topology and dev config |
| `resources/import.sql` | Seed medications |
| `spec.md`, `DESIGN.md`, `notes.md` | Spec, design/decisions, scratch notes |

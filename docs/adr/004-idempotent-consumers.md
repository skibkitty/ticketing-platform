# ADR 004: Idempotent Kafka consumers via a processed_events table

**Status:** Accepted

**Context:** The outbox pattern (ADR 003) guarantees at-least-once delivery,
so a consumer may see the same message more than once.

**Decision:** Each consumer keeps a `processed_events` table keyed on the
message's `eventId`. On receipt: skip if already recorded; otherwise apply
the business logic and insert the processed-event row in the same
transaction.

## How payment-service makes the check race-proof

The old "check `existsById`, then insert" flow had a TOCTOU race: two
concurrent deliveries of the same `eventId` could both see "not processed"
and both enter the business logic, where the `reservation_id` uniqueness
constraint — not the idempotency check — was the only thing that would trip,
turning a legitimate duplicate into a database exception that got retried and
dead-lettered. That is fixed by replacing the check with an **atomic claim**:

```sql
INSERT INTO payment.processed_events (event_id) VALUES (:eventId)
ON CONFLICT (event_id) DO NOTHING
```

which returns 1 when this transaction won the claim and 0 when the event was
already claimed (by an earlier or by a concurrently committing transaction).
A duplicate — sequential **or concurrent** — observes 0 rows and returns as a
successful no-op; it is never an integrity exception and never routed to the
DLT.

The claim runs **inside the same transaction** that writes the payment and
the outbox row:

- first delivery of an eventId claims it, applies the payment + outbox work,
  commits;
- duplicate/concurrent delivery of that eventId is a successful no-op;
- if the payment/outbox work fails, the whole transaction rolls back and the
  claim goes with it, so Kafka can redeliver the event cleanly.

## One payment per reservation

Payment is a one-per-reservation saga step, so the money step is claimed per
reservation as well, atomically:

```sql
INSERT INTO payment.payments (reservation_id, amount_cents, status, settled_at)
VALUES (:reservationId, ...)
ON CONFLICT (reservation_id) DO NOTHING
```

A second `ReservationCreated` for a reservation that already has a payment —
even one bearing a **different** `eventId` — is therefore a deterministic
no-op (logged, and its `eventId` still recorded in `processed_events`)
instead of a uniqueness violation. That is a deliberate choice: a
differently-ID'd repeat is an upstream replay/anomaly, not a signal to fail
the money step, and it must never spin into an opaque retry/DLT loop.

**Consequences:** Duplicate delivery becomes a no-op instead of, say,
double-charging a seat release. `processed_events` grows unboundedly with no
retention policy — a known gap, worth naming if asked.

**Publication contract:** reservation-service publishes to
`reservation.events.v1` at-least-once. Rows are claimed with
`FOR UPDATE SKIP LOCKED`, but a broker ack that lands after a crash but before
the claiming transaction commits still re-publishes the row; an `eventId` is
persisted per outbox row (V3/V4) so every attempt carries the **same**
`eventId`. Every consumer of `reservation.events.v1` **must** dedupe on the
envelope's `eventId` using this table. This is a hard contract, not an
optimisation.
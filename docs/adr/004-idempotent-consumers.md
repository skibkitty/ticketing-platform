# ADR 004: Idempotent Kafka consumers via a processed_events table

**Status:** Accepted

**Context:** The outbox pattern (ADR 003) guarantees at-least-once delivery,
so a consumer may see the same message more than once.

**Decision:** Each consumer keeps a `processed_events` table keyed on the
message's `eventId`. On receipt: skip if already recorded; otherwise apply
the business logic and insert the processed-event row in the same
transaction.

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

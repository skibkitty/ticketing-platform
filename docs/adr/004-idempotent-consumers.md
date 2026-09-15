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

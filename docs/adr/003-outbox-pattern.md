# ADR 003: Transactional outbox instead of dual-write to DB + Kafka

**Status:** Accepted

**Context:** A service needs to atomically change its own state (e.g. hold
seats, create a reservation) and notify other services of that change. The
database and Kafka have no shared transaction.

**Decision:** Write the event to an `outbox_events` table in the same
transaction as the state change. A scheduled poller reads unpublished rows
and sends them to Kafka, then marks them published.

**Consequences:** At-least-once delivery — an event is never silently lost,
but may be sent twice on publisher crash/retry, which is why every consumer
must be idempotent (ADR 004). Adds an outbox table + poller per
event-producing service (`reservation-service` and `payment-service`).

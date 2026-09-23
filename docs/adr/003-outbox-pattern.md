# ADR 003: Transactional outbox instead of dual-write to DB + Kafka

**Status:** Accepted

**Context:** A service needs to atomically change its own state (e.g. hold
seats, create a reservation) and notify other services of that change. The
database and Kafka have no shared transaction.

**Decision:** Write the event to an `outbox_events` table in the same
transaction as the state change. A scheduled poller reads unpublished rows
and sends them to Kafka, then marks them published.

## Publication mechanics (payment-service)

Payment-service's poller reads a candidate batch (`SELECT ... WHERE
published_at IS NULL LIMIT 20 FOR UPDATE SKIP LOCKED`) without holding any
locks, then hands each row to its own `REQUIRES_NEW` transaction. Inside
that transaction the row is re-claimed with `SELECT ... FOR UPDATE WHERE
id = ? AND published_at IS NULL` — so two publisher instances can never both
own the same row — then sent to Kafka, then `published_at` is written and
committed.

- a **successful send** commits `published_at`;
- a **failed send** throws, the transaction commits with no changes, the row
  lock is released, and the next poll re-claims and re-publishes it;
- a row **already published by a concurrent poller** is skipped by the
  re-claim predicate.

Only one row lock is held at a time and only for the duration of that row's
single Kafka send, never for the whole batch.

**Consequences:** At-least-once delivery — an event is never silently lost,
but may be sent twice:

1. a failed send is retried on the next poll, and
2. if the process crashes after Kafka acknowledges the send but before the
   `published_at` commit, the row is still unpublished and is sent again.

Every publish attempt carries the **same** `eventId` (persisted per outbox
row), so a duplicate send is harmless to any consumer that dedupes on it
(ADR 004). Adds an outbox table + poller per event-producing service
(`reservation-service` and `payment-service`).

This is **at-least-once publication, not exactly-once**. Moving to exactly
-once would require Kafka transactions / an idempotent producer plus a
commit that atomically covers both the Kafka send and the `published_at`
write — not implemented here.
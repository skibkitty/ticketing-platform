# ADR 009: processed_events retention

**Status:** Accepted

**Context:** `processed_events` is the append-only idempotency log (ADR 004):
one row per claimed inbound `eventId`, written and committed in the same
transaction as the business change it deduplicates. It has no retention
policy today and grows unboundedly.

**Decision:** Retention must be at least the Kafka re-delivery horizon, i.e.
`max(source-topic retention across every topic this service consumes, DLT
re-drive window) + margin`. A claimed row can only be deleted once it is
certain the same `eventId` can never be delivered again:

- Kafka's default log retention is 7 days (`log.retention.hours=168`); the
  deployed brokers must be configured at least this high for topics this
  service consumes, and this horizon is the floor for `processed_events`.
- The DLT (ADR 008) has no re-drive tooling yet, so the re-drive window is
  an operational decision; a 30-day retention covers a deliberate re-drive
  of even a week-old batch.
- A purge is a periodic statement, run outside the consumer transaction:

```sql
DELETE FROM reservation.processed_events
WHERE processed_at < now() - INTERVAL '30 days';
```

Only rows older than the horizon may be deleted — never rows that could
still be redelivered, or a replay would double-apply (the exact regression
the table exists to prevent).

**Consequences:** `processed_events` is bounded by the retention window rather
than unbounded. The purge is deliberately conservative and cheap (the table is
keyed on `event_id`). No cleanup job exists yet; this ADR records the
contract a future one must honour.
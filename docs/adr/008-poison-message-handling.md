# ADR 008: Poison-message handling for payment-service (retry then dead-letter)

**Status:** Accepted

**Context:** Idempotency (ADR 004) covers duplicate delivery, but a *malformed*
record — unparseable JSON, a payload that cannot be deserialized, or a
producer bug such as a negative `amountCents` — is a different class of
failure. Spring Boot's default listener error handling is implicit: with no
handler configured, a failing record is retried according to defaults and can
otherwise be committed-and-dropped or retry forever, depending on version.
Neither behaviour is a deliberate contract.

**Decision:** payment-service defines an explicit `CommonErrorHandler` for its
Kafka consumer:

- transient failures are retried with `FixedBackOff(1000ms, 5 attempts)`;
- a record that still fails is published to `<original-topic>.dlt` by a
  `DeadLetterPublishingRecoverer`, so poison is quarantined for inspection
  instead of being dropped or looping;
- `spring.kafka.listener.ack-mode: record` so a poison record is retried and
  dead-lettered without re-delivering the rest of its polled batch.

Because the effect, outbox row, and `processed_events` row are written in one
transaction (ADR 004), a failing record always rolls back cleanly before any
retry or DLT publish — there is never a half-applied payment.

**Consequences:** malformed events land on `reservation.events.v1.dlt` and are
auditable. The DLT has no re-drive tooling yet — a known operational gap for a
follow-up. The same policy is applied solely to payment-service; the T04
reservation consumer predates this and keeps its implicit default behaviour.

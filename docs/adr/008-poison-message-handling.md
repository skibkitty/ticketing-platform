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
Kafka consumer (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`):

- transient failures are retried with `FixedBackOff(1000ms, 5 attempts)`;
- a record that still fails is published to `<original-topic>.dlt` by the
  `DeadLetterPublishingRecoverer`, so poison is quarantined for inspection
  instead of being dropped or looping;
- `spring.kafka.listener.ack-mode: record` so a poison record is retried and
  dead-lettered without re-delivering the rest of its polled batch; after the
  recoverer runs, the offset is committed and the next record is processed.

## What is and is not transactional

`JpaPaymentProcessingService.process()` is `@Transactional`, but that is a
**database** transaction only — the Kafka listener is **not** a Kafka
transaction and the producer is not transactional. The precise guarantees:

- the payment row, the outbox row, and the `processed_events` claim are
  written in **one local DB transaction** (ADR 004). When that transaction
  fails, the consumer's error handler sees the exception and the record is
  retried and, on exhaustion, dead-lettered. On a rollback there is never a
  half-applied payment or a leftover processed-event claim.
- the list of exceptions that go down the retry/DLT path is exactly: envelope
  JSON that cannot be parsed; an envelope that is structurally malformed
  (null/blank `eventType`, null `eventId`, null `payload`); a payload that is
  invalid for a **supported** event type (missing/zero ids, empty/non-positive
  `seatIds`, missing or negative `amountCents`); and any unexpected runtime
  failure inside `process()`.
- **idempotency races are not poison.** A duplicate/concurrent delivery of the
  same `eventId` is resolved by the atomic `processed_events` claim and returns
  normally; a second `ReservationCreated` for an already-paid reservation is a
  no-op. Neither raises an exception, so neither can be retried into the DLT.
- unsupported-but-well-formed event types on the topic (e.g.
  `reservation.ReservationConfirmed`) are deliberately ignored — not parsed for
  business rules, not marked processed, not dead-lettered.

## Correlation ids

Correlation ids are optional at the platform edge, never mandatory: the web
`CorrelationIdFilter` and reservation-service's producer both mint a UUID when
absent. The payment consumer therefore accepts a missing/blank id rather than
rejecting the message; it resolves to the Kafka header, then the envelope's
`correlationId`, then a freshly minted UUID so the MDC always carries a
non-blank trace id. See `ReservationCreatedConsumer`.

**Consequences:** Malformed events land on `reservation.events.v1.dlt` and are
auditable. The DLT has no re-drive tooling yet — a known operational gap for a
follow-up. The same policy is applied solely to payment-service; the T04
reservation consumer predates this and keeps its implicit default behaviour.
Inbound consumption is at-least-once: the same decision applies to outbound
publication (ADR 003).

## reservation-service's confirmation consumer

reservation-service's `JpaReservationConfirmationService` applies the same
error-handling shape to `payment.events.v1` (retry then `<topic>.dlt`), with one
deliberate difference in classification. Its topics (`payment.events.v1` and
`payment.events.v1.dlt`) are self-provisioned via `KafkaAdmin` (see
`KafkaTopicConfig`) instead of relying on broker auto-creation. Event types are
classified explicitly, never swallowed:

- `payment.PaymentSucceeded` — confirms a pending reservation (idempotent
  confirm, ADR 004/007), and the payload's `status` must be `SUCCEEDED` —
  a record whose event type claims success while its payload disputes it is a
  producer bug that takes the retry/DLT path.
- `payment.PaymentFailed` — the compensating step (T07): cancels the pending
  reservation and releases its seats back to `AVAILABLE`, claimed idempotently
  under the same ADR 004/007 guards. A **well-formed** PaymentFailed is handled
  and therefore never dead-lettered — it is a known type, not quarantine
  fodder. Malformed variants are still classified poison like any other failing
  record: a missing/non-positive `reservationId`, an unknown reservation, an
  unparseable payload, or a `status` that disputes the event type (must be
  `FAILED`) all raise and take the retry/DLT path.
- anything else — an unrecognized event on the topic; rejected with an exception
  so the record takes the retry/DLT path and stays auditable instead of being
  silently acknowledged.

Wire contract: on a `payment.PaymentSucceeded` the payload's `status` is always
`SUCCEEDED`. payment-service derives both the event type and the payload status
from the same persisted payment row (`payment.getStatus()`), so the two can
never diverge for a legitimate message. reservation-service still validates
`status == "SUCCEEDED"` as defense in depth: a record whose event type claims
success while its payload disputes it is a producer bug that should reach the
DLT, never be silently accepted.
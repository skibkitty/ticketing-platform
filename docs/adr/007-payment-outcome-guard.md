# ADR 007: Ignore payment outcomes for reservations no longer pending payment

**Status:** Accepted

**Context:** payment-service's outcome is asynchronous, so a `PaymentSucceeded`/`PaymentFailed` created just before the hold expires can arrive *after* the sweep has already marked the reservation `EXPIRED` and released its seats. Applying that late outcome to a no-longer-pending reservation would re-flip seats the reservation no longer owns; the `@Version` conflict would roll back the whole transaction including the `processed_events` insert, turning the message into a poison event redelivered forever.

**Decision:** The outcome consumer in reservation-service only applies a payment outcome while the reservation is `PENDING_PAYMENT`. Otherwise it records the event as processed and does nothing.

**Consequences:** A settled-but-late outcome never revives the reservation — the 10-minute hold is binding and the seats may already belong to someone else. No reconcile loop, no poison messages, and a `PaymentFailed` arriving after expiry leaves the reservation `EXPIRED`, not `CANCELLED`, and publishes nothing (nothing was charged). The money side is reconciled rather than dropped: a `PaymentSucceeded` ignored on an `EXPIRED` reservation writes a `LatePaymentRejected` to the outbox in the same transaction, and payment-service refunds it ([ADR 017](017-refund-late-settled-payment.md)).

**Gotchas (from the T14 walkthrough):**

- Only `EXPIRED` reports a late success for refund. A `CONFIRMED` reservation
  that sees another `PaymentSucceeded` keeps its money (the Customer has the
  Seats), and `CANCELLED` is reached only by a `PaymentFailed`, so a success
  after it is a producer bug, not money that came too late. Before #52 the late
  success was dropped outright, leaving `GET /api/v1/payments/{id}` at
  `SUCCEEDED` beside an `EXPIRED` Reservation; it now reads `REFUNDED`.
- The Hold is the only clock. A slow or down payment-service turns every
  Reservation placed meanwhile into an expiry, which is also the only way to
  watch one happen by hand: `docker compose stop payment-service`.

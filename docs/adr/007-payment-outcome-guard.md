# ADR 007: Ignore payment outcomes for reservations no longer pending payment

**Status:** Accepted

**Context:** payment-service's outcome is asynchronous, so a `PaymentSucceeded`/`PaymentFailed` created just before the hold expires can arrive *after* the sweep has already marked the reservation `EXPIRED` and released its seats. Applying that late outcome to a no-longer-pending reservation would re-flip seats the reservation no longer owns; the `@Version` conflict would roll back the whole transaction including the `processed_events` insert, turning the message into a poison event redelivered forever.

**Decision:** The outcome consumer in reservation-service only applies a payment outcome while the reservation is `PENDING_PAYMENT`. Otherwise it records the event as processed and does nothing.

**Consequences:** A settled-but-late outcome is dropped rather than reconciled — the 10-minute hold is binding and the seats may already belong to someone else. No reconcile loop, no poison messages, and a `PaymentFailed` arriving after expiry leaves the reservation `EXPIRED`, not `CANCELLED`.
**Gotchas (from the T14 walkthrough):**

- *Dropped* means the money is not reconciled either. Stop payment-service, let
  a Hold lapse, and start it again: it settles the old `ReservationCreated`, and
  `GET /api/v1/payments/{id}` reads `SUCCEEDED` while the Reservation reads
  `EXPIRED` with its Seats back on sale. In a system with a real charge that is a
  customer paying for nothing; the guard makes the reservation side safe, and a
  refund (a compensating `Refund` on payment-service for an outcome the guard
  ignored) is the missing other half.
- The Hold is the only clock. A slow or down payment-service turns every
  Reservation placed meanwhile into an expiry, which is also the only way to
  watch one happen by hand: `docker compose stop payment-service`.

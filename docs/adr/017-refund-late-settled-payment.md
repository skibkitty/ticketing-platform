# ADR 017: Refund a payment that settles after its Hold expired

**Status:** Accepted

**Context:** ADR 007 makes a late payment outcome safe on the reservation side:
a `PaymentSucceeded` that lands after the sweep has expired the Reservation is
claimed and ignored. Nothing reconciled the payment side, so the platform could
end with the Reservation `EXPIRED`, its Seats back on sale, and the Payment
`SUCCEEDED`: with a real charge, a Customer who paid for nothing and was told
only that their Hold lapsed ([#52](https://github.com/skibkitty/ticketing-platform/issues/52)).

Only reservation-service knows that an outcome came too late, and it knows at
one moment: the ADR 007 branch. payment-service has no view of Reservation
state (ADR 005), and giving it one would make the saga orchestrated in one
direction and choreographed in the other.

**Decision:** The refund is the compensating step for the payment side, run by
choreography like every other step.

1. When reservation-service ignores a `PaymentSucceeded` because the
   Reservation is `EXPIRED`, it writes `reservation.LatePaymentRejected`
   (`reservationId`, `paymentId`) to its outbox in the same transaction as the
   `processed_events` claim (ADR 003, ADR 004). No other non-pending status
   reports anything (see ADR 007's gotcha).
2. payment-service, which already consumes `reservation.events.v1`, moves the
   Payment from `SUCCEEDED` to a new terminal `REFUNDED` state, sets
   `refunded_at`, and writes `payment.PaymentRefunded` to its own outbox, all
   in one transaction. A Refund is a state of the one Payment per Reservation,
   not a record of its own.
3. notification-service, now also consuming `payment.events.v1`, records a
   `PAYMENT_REFUNDED` Notification for the Payment's owner.
4. A late `PaymentFailed` changes nothing and publishes nothing: nothing was
   charged.

**Consequences:**

- One refund and one notice per late success, however often anything is
  redelivered. reservation-service publishes inside its event claim, so at most
  one `LatePaymentRejected` per outcome event. payment-service dedupes on the
  event id and refunds with a status-guarded
  `UPDATE ... WHERE status = 'SUCCEEDED'`, so a repeat with a different id is a
  no-op once the Payment is `REFUNDED`, and only the transaction that won the
  transition writes `PaymentRefunded`. notification-service dedupes as it
  already does, and its `(reservation_id, type)` constraint holds one
  `PAYMENT_REFUNDED` per Reservation.
- `payment.events.v1` is no longer only outcomes. reservation-service ignores
  `PaymentRefunded` explicitly rather than dead-lettering it as an unsupported
  type; any other unknown type is still dead-lettered (ADR 008).
- `GET /api/v1/payments/{reservationId}` can now answer `REFUNDED`, with
  `refundedAt` beside `settledAt`.
- A Payment that predates its owner being stored (ADR 016) is refunded like any
  other, but `PaymentRefunded` carries a null `customerId`, so
  notification-service logs a warning and sends no notice instead of
  dead-lettering it. The money is right; the notice is the part that cannot be
  delivered.
- The refund itself is simulated, like the charge. A real payment provider
  would make this step fallible, and it would need the same retry and
  dead-letter treatment as any other consumer.

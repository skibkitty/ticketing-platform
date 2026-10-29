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
- `RESERVATION_EXPIRED` and `PAYMENT_REFUNDED` reach notification-service on
  different topics, so Kafka doesn't order them. Normally the expiry has been
  notified long before the payment even settles. But a notification-service
  that's far behind on `reservation.events.v1` and caught up on
  `payment.events.v1` could record the refund first, and the inbox, ordered by
  send time, would show it first. Both notices still arrive exactly once.
- The refund itself is simulated, like the charge. A real payment provider
  would make this step fallible, and it would need the same retry and
  dead-letter treatment as any other consumer.

## Rollout and recovery

The change spans three services and two event contracts, and an older build
mishandles each new piece in a different way:

| An older… | receiving… | does | consequence |
| --- | --- | --- | --- |
| payment-service | `LatePaymentRejected` | ignores it and commits the offset (any non-`ReservationCreated` type is ignored) | **the refund is silently skipped** |
| reservation-service | `PaymentRefunded` | rejects it as an unsupported type → retries → `payment.events.v1.dlt` | DLT noise only, nothing is lost |
| notification-service | `PaymentRefunded` | never sees it (not subscribed) | the notice waits in the topic |
| payment-service | a `REFUNDED` row | cannot map the status | `GET /payments/{id}` fails for that Payment; a duplicate `ReservationCreated` for it is retried and dead-lettered |

**Deploy order.** Consumers before producers:

1. **notification-service.** It joins `payment.events.v1` with no committed
   offset, so it reads the topic from the start (`auto-offset-reset: earliest`).
   Only `PaymentRefunded` is acted on and none exist yet, so the read is harmless.
2. **payment-service.** Flyway applies V5 (one nullable column, no backfill, no
   lock beyond the `ALTER TABLE`). It now understands `LatePaymentRejected`, but
   nothing sends one yet, so it never publishes `PaymentRefunded` to an older
   reservation-service.
3. **reservation-service.** Only now does anything emit `LatePaymentRejected`.

**Rolling back.** Reverse the order, and don't assume an application rollback
is enough:

- Roll **reservation-service** back first, so nothing new asks for a refund.
  `PaymentRefunded` events still in flight are dead-lettered by the older build.
  That's harmless, and those records can be dropped.
- **notification-service** can roll back at any time. Notices it hasn't recorded
  yet stay in `payment.events.v1` until it's redeployed (topic retention
  permitting, ADR 009).
- **payment-service** can roll back only if **no Payment is `REFUNDED`**
  (`SELECT count(*) FROM payment.payments WHERE status = 'REFUNDED'`). Once one
  exists, fix forward instead of rolling back: the older build cannot read
  those rows. Do not "repair" them by writing `SUCCEEDED` back, because that
  would erase the record of money already returned. The V5 column itself is safe
  to leave in place: Flyway's default `ignoreMigrationPatterns` (`*:future`)
  lets the older build validate and start against a V5 database, and the older
  code never reads `refunded_at` (`PaymentRefundMigrationTests`). Never drop it
  by hand.

**Recovering skipped refunds.** If an older payment-service consumed any
`LatePaymentRejected` (for example, reservation-service was deployed first, or
payment-service was rolled back while reservation-service stayed new), those
refunds never ran. Every one is still recorded in `reservation.outbox_events`
(`event_type = 'reservation.LatePaymentRejected'`) and, within retention, in
`reservation.events.v1`. With the new payment-service running, replay the
topic from before the incident:

```bash
docker compose stop payment-service
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --group payment-service --topic reservation.events.v1 --reset-offsets --to-datetime <ISO-8601> --execute
docker compose start payment-service
```

If the topic no longer holds them (past retention, or a broker without
persistent storage, like compose's Kafka, which keeps no volume), re-publish
them from the outbox instead. Clear `published_at` on those
`reservation.outbox_events` rows, and the outbox publisher sends them again
with their original `eventId` (ADR 003):

```sql
UPDATE reservation.outbox_events SET published_at = NULL
WHERE event_type = 'reservation.LatePaymentRejected' AND created_at >= '<ISO-8601>';
```

The replay is safe because every consumer dedupes (`processed_events`), the
Payment's one-per-Reservation constraint holds, the refund's status guard
applies, and Notifications are unique per Reservation and type. The platform
stack test replays every topic from offset zero to prove it
(`LateSettlementRefundStackTest`).

# Architecture

## The story

A customer holds one or more seats for an event, pays, and the hold either
converts to a confirmed ticket or — if payment fails — the seats go back on
sale automatically. Two things make this harder than a CRUD app: many
customers can try to grab the same seat at once (concurrency), and "pay" is
a step that can fail *after* the seat is already held (which needs a
rollback that spans two services, not one database transaction).

## Domain model

- **Event** — name, venue, date. Owns many Seats.
- **Seat** — section/row/number, `status` (`AVAILABLE`, `HELD`, `SOLD`),
  `holdExpiresAt`, `version` (optimistic lock).
- **Reservation** — a customer's attempt to buy a specific set of seats.
  `status` (`PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED`).
- **Payment** — one per reservation. `status` (`PENDING`, `SUCCEEDED`,
  `FAILED`).

`reservation-service` owns Event, Seat, and Reservation together —
deliberately not split into a separate "catalog service," see below.
`payment-service` owns Payment.

## The saga (the part that isn't a toy CRUD app)

```
Customer -> POST /api/v1/reservations {eventId, seatIds}
  -> reservation-service, ONE local transaction:
       - optimistically lock + flip each requested Seat: AVAILABLE -> HELD
         (holdExpiresAt = now + 10 min)
       - any seat already taken -> whole transaction rolls back, 409 to the
         customer, no partial holds
       - create Reservation (PENDING_PAYMENT)
       - write an OutboxEvent for ReservationCreated
  -> OutboxPublisher sends it to reservation.events.v1

  -> payment-service consumes ReservationCreated
       - simulates a payment attempt
       - writes PaymentSucceeded or PaymentFailed to payment.events.v1

  -> reservation-service consumes payment.events.v1
       - PaymentSucceeded: Reservation -> CONFIRMED, Seats -> SOLD,
         publish ReservationConfirmed
       - PaymentFailed: Reservation -> CANCELLED, Seats -> AVAILABLE
         (the compensating action), publish ReservationCancelled

  -> notification-service consumes ReservationConfirmed/Cancelled/Expired
       - records a Notification, logs a simulated send

Separately, a scheduled sweep in reservation-service finds PENDING_PAYMENT
reservations past holdExpiresAt with no payment outcome yet, releases their
seats, marks the reservation EXPIRED, and publishes ReservationExpired.
```

This is a **choreographed saga**, not an orchestrated one: there's no
separate "saga coordinator" service issuing commands. `reservation-service`
initiates it (by publishing `ReservationCreated`) and later reacts to
`payment-service`'s outcome — each service only knows about events, not
about calling the other directly.

## Why optimistic locking on Seat, not pessimistic (`SELECT ... FOR UPDATE`)

A reservation can touch several seats in one transaction. Taking pessimistic
row locks on multiple seats means the *order* those locks are acquired in
matters — two concurrent reservations locking the same two seats in opposite
order is a textbook deadlock. Optimistic locking (`@Version`) avoids
acquiring any lock at all: each seat update just includes a `WHERE version =
?` check, and if a concurrent transaction already moved that seat, the
update affects zero rows and the whole reservation transaction rolls back
with a clear "some seats are no longer available" response. Contention on a
specific seat is expected to be rare outside of the first seconds of a
flash sale, so we optimize for that common case rather than paying for a
lock on every hold attempt.

## Why is the *entire* seat-hold + reservation-create step one local transaction, not a separate call to a "catalog service"?

This is the one place in the system where atomicity has to be airtight:
holding seat A but not seat B because of a mid-operation failure is exactly
the double-booking / half-reservation bug this design exists to prevent. A
single local ACID transaction gives that for free. Splitting Event/Seat into
their own service would turn this into a distributed transaction across two
databases — solvable, but only with a two-phase commit or (ironically)
another saga, adding real complexity to buy service-boundary purity that
isn't needed here. `reservation-service` and `payment-service` *are* split,
specifically because the boundary between "hold seats" and "charge money" is
the one place in this flow that's naturally asynchronous and allowed to fail
independently.

## Why the payment failure gets "compensated" instead of retried

A declined card isn't a transient fault — retrying the same payment attempt
won't turn a decline into a success. The correct response to "payment
failed" is to undo the reservation's side effect (release the seats), not to
resend the payment request. (A transient payment-gateway timeout would be a
different case, worth a retry at the HTTP-client level inside
payment-service itself — but that's a retry *within* the payment step, not a
reason to change what reservation-service does when it receives a terminal
`PaymentFailed`.)

## Why a 10-minute hold expiry, and why a scheduled sweep instead of a delay-queue/TTL mechanism

Ten minutes is enough time for a customer to complete a checkout form
without being so long that seats sit needlessly unavailable if they abandon
the purchase. A scheduled poll (`@Scheduled`, checking for
`PENDING_PAYMENT` rows past their expiry) is the simplest mechanism that
satisfies a minutes-scale deadline — a Kafka delayed-message pattern or a
Redis key-expiry-notification setup would add real infrastructure for a
guarantee (sub-second precision) this use case doesn't need.

## Why rate limiting + a bulkhead specifically on `POST /api/v1/reservations`, and nowhere else

This is the flash-sale endpoint — the one place a popular event's on-sale
moment produces a traffic spike orders of magnitude above normal load, often
from bots. A `RateLimiter` caps the request rate the gateway will forward
for that route; a `Bulkhead` caps how many of those requests can be
*in flight* at once, so a slow downstream response can't let concurrent
reservation attempts pile up and exhaust the gateway's threads. Neither is
applied to, say, `GET /api/v1/events` — browsing traffic doesn't have the
same spike profile or the same cost-per-request (a seat hold does real
write work; a browse is a cheap read).

## Why a circuit breaker + fallback on the payment-status query route, specifically

`GET /api/v1/payments/{reservationId}` is the one gateway route that's a
synchronous proxy call to a service (`payment-service`) that could be
briefly overloaded by its own consumer workload. Wrapping it in a circuit
breaker means a struggling payment-service degrades this one
non-critical status-lookup route to a fast, clear "temporarily unavailable"
response instead of a hanging request — it doesn't block the reservation
flow itself, since that's fully async via Kafka.

## Why JWT validation and CORS live only at the gateway

One trust boundary to audit and rotate keys for; downstream services trust
the `X-User-Roles` header the gateway sets, which only holds because the
internal services aren't reachable except through the gateway. See
`docs/adr/002-gateway-trust-boundary.md` for the tradeoff this creates.

## What's intentionally out of scope

- **A real payment gateway integration** — `payment-service` simulates an
  outcome (see `PAYMENT_DECLINE_THRESHOLD_CENTS`) rather than calling
  Stripe/etc. The interesting part is the saga's compensating action, not an
  actual PCI-scope payment integration.
- **A public "waiting room" queue for on-sale moments** — real ticketing
  platforms (Ticketmaster-style) put customers in a queue *before* they can
  even attempt a reservation, on top of rate limiting the reservation
  endpoint itself. Worth describing as the natural next layer if asked, not
  built here to keep this reviewable in one sitting.
- **Distributed tracing** — correlation IDs give manual, log-based tracing
  across the whole flow; no OpenTelemetry collector/UI.
- **Schema registry for Kafka events** — plain JSON via the shared
  `EventEnvelope` record; no Avro/Protobuf + registry.

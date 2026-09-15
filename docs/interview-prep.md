# Interview Prep: Defending This System

## The 30-second pitch

> "It's an event ticketing platform: customers hold seats, pay, and the hold
> either confirms or — if payment fails — releases automatically. The two
> hard parts are concurrency, since many people can try to grab the same
> seat at once, and consistency across services, since 'hold the seat' and
> 'charge the card' can't be one database transaction. I used optimistic
> locking for the first and a choreographed saga with a compensating action
> for the second."

## Numbers to know cold

| Fact | Value |
|---|---|
| Deployable services | 4 (reservation-service, payment-service, notification-service, api-gateway) |
| Kafka topics | 2 — `reservation.events.v1`, `payment.events.v1` |
| Event types | `ReservationCreated`, `PaymentSucceeded`, `PaymentFailed`, `ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired` |
| Saga type | Choreographed (no central orchestrator service) |
| Concurrency control | Optimistic locking (`@Version`) on `Seat`, not pessimistic locks |
| Hold expiry | 10 minutes, released by a scheduled sweep |
| Delivery guarantee | At-least-once; idempotency via `processed_events` keyed on `eventId` |
| Gateway resilience | RateLimiter + Bulkhead on `POST /reservations`; CircuitBreaker on `GET /payments/{id}` |
| Where auth/CORS live | api-gateway only |

## Question bank

**Q: Two customers click "buy" on the same seat at the same instant. What happens?**
A: Both requests read the seat as `AVAILABLE`. Both try to update it to
`HELD`. Because of the `@Version` column, only one update actually succeeds
— the other affects zero rows, the whole reservation transaction for that
loser rolls back, and they get a clean 409 telling them the seat's gone.
No lock was ever held; the conflict is detected, not prevented.

**Q: Why not just lock the seat row while you check and update it?**
A: A reservation can span multiple seats. Acquiring pessimistic locks on
several rows means lock *order* matters — two reservations locking the same
two seats in opposite order deadlock. Optimistic locking sidesteps that
entirely: no lock is held across the transaction, so there's nothing to
order or deadlock on.

**Q: Walk me through what happens end-to-end when a payment fails.**
A: payment-service publishes `PaymentFailed` to `payment.events.v1`.
reservation-service's consumer picks it up, checks it hasn't already
processed that event ID, then in one transaction: sets the Reservation to
`CANCELLED`, flips its Seats back to `AVAILABLE`, clears their hold
expiry, and writes a `ReservationCancelled` outbox event for
notification-service to pick up.

**Q: Is that a saga? What kind?**
A: Yes — a choreographed one. There's no dedicated orchestrator service
issuing "now do X" commands; reservation-service kicks it off by publishing
an event, and reacts to whatever payment-service eventually publishes back,
without either service calling the other directly.

**Q: Why choreography instead of an orchestrator service?**
A: There are only two steps (hold seats, charge payment) and one
compensating action. An orchestrator earns its complexity once there are
several steps with branching compensation logic across many services —
here it would just be a third service relaying two message types back and
forth, adding a hop for no real benefit.

**Q: Why can a declined payment not just be retried automatically?**
A: A decline isn't a transient failure — retrying the identical charge
won't turn a "no" into a "yes." The correct response is to undo the
reservation's side effect (release the seats), not resend the same request.
A genuinely transient failure (gateway timeout) would be retried inside
payment-service's own HTTP client, before it ever publishes a terminal
`PaymentFailed`.

**Q: What's rate limiting protecting against here, specifically?**
A: The on-sale moment for a popular event — a traffic spike far above
normal load, often bot-driven, hitting exactly one endpoint
(`POST /reservations`). It's not applied platform-wide because browsing
traffic doesn't have that spike profile or that per-request cost.

**Q: What's the difference between the RateLimiter and the Bulkhead on that route?**
A: RateLimiter caps how many requests per time window are let through at
all. Bulkhead caps how many are allowed to be *in flight concurrently* —
protecting against a slow downstream response letting requests pile up and
exhaust gateway threads, which a pure rate limit alone doesn't prevent if
each request takes a long time to complete.

**Q: What happens if a customer holds seats and just never pays?**
A: The scheduled sweep finds the reservation once `expires_at` (10 minutes
after hold) has passed with no payment outcome recorded, releases the
seats, and marks the reservation `EXPIRED` — same seat-release code path
conceptually as a payment failure, just triggered by a timeout instead of
an event.

**Q: Why is Event/Seat data owned by reservation-service instead of its own catalog-service?**
A: Because holding a seat and creating the reservation record have to be
one atomic operation — that's the one place double-booking would actually
happen if it weren't atomic. Splitting Seat into a separate service would
turn that into a distributed transaction across two databases for the one
step where atomicity matters most. payment-service is split out specifically
because charging money *is* naturally a separate, independently-failable
step.

## "Why not X" traps

- **Why not just use a database lock/queue instead of Kafka for the saga?**
  A DB-level solution works within one database; the point here is
  coordinating two services with two separate databases. Kafka gives durable,
  replayable messaging between them without either service needing direct
  network access to the other's internals.
- **Why not Redis for the seat holds (TTL-based) instead of a DB column + sweep?**
  Reasonable alternative — Redis TTLs give automatic expiry without a
  polling job. The tradeoff: the seat's authoritative state would then live
  in two places (Postgres for the sold/available truth, Redis for the hold
  timer), which is its own consistency problem. Keeping it in one row in one
  database, checked by a simple sweep, is one less system to keep in sync at
  this scale.
- **Why not make the whole flow synchronous (call payment-service directly
  and wait for the response)?** Payment processing has unpredictable
  latency and failure modes; making the customer's reservation request wait
  on it (and fail if payment-service is briefly down) couples two things
  that don't need to be coupled in real time. Async lets seat-holding
  succeed and return immediately regardless of payment-service's health.

## Known gaps — bring these up yourself

- No real payment gateway — payment-service simulates an outcome by
  threshold. Fine to say plainly: "the saga mechanics are the point, not a
  PCI-scope integration."
- No pre-reservation "waiting room" queue — real ticketing platforms queue
  customers *before* they can even attempt a reservation during a big
  on-sale; this project's rate limiter protects the endpoint but doesn't
  implement a queueing UX on top of it.
- `processed_events` has no retention/cleanup job.
- Static JWT secret, no rotation/JWKS story.
- No distributed tracing — correlation IDs give log-based tracing only.

## Drill format

Cover the answer, say it out loud in one breath, check. Whatever category
you stumble on is the one to reread tonight, not the one to reread in the
lobby.

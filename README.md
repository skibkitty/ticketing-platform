# Ticketing Platform

An event ticketing & reservation system: seat holds with optimistic
concurrency control, a payment saga with automatic compensation on failure,
and gateway-level rate limiting for flash-sale traffic.

**Start here:**
1. [`docs/architecture.md`](docs/architecture.md) — what this is and why
   every non-obvious decision was made the way it was.
2. [`docs/tasks.md`](docs/tasks.md) — the ordered, atomic implementation
   checklist.
3. [`docs/adr/`](docs/adr) — short decision records for the six main
   choices; fill these in as you build (task T44).

## For opencode / whoever implements this

Scaffolding already exists: parent + module `pom.xml`s, `docker-compose.yml`,
per-module `Dockerfile`s, package directories, CI workflow. Verify `mvn -q
compile` before writing feature code, then work `docs/tasks.md` in order —
payment-service's consumer can't be meaningfully tested until
reservation-service actually publishes `ReservationCreated`.

Double check before building: the Spring Cloud release-train version in the
root `pom.xml` against the Spring Boot 3.4.5 compatibility matrix
(https://spring.io/projects/spring-cloud#overview), and the `apache/kafka`
Docker image tag in `docker-compose.yml`.

## Running it

```bash
docker compose up --build
```

- Gateway: `http://localhost:8080`
- Kafka UI: `http://localhost:8090`
- Direct service access (debugging): reservation-service `:8082`,
  payment-service `:8083`, notification-service `:8084`

## Example requests

*(To be filled in with real, tested commands as part of task T45.)*

```bash
# 1. Log in
curl -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"organizer","password":"organizer"}'

# 2. Create an event with seats (ORGANIZER/ADMIN token)
curl -X POST localhost:8080/api/v1/events \
  -H "Authorization: Bearer <organizer-token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Radiohead","venue":"The Fillmore","eventDate":"2026-11-01T20:00:00Z","seats":[{"section":"A","row":1,"seatNumber":1,"priceCents":15000},{"section":"A","row":1,"seatNumber":2,"priceCents":15000}]}'

# 3. Browse available seats
curl localhost:8080/api/v1/events/1/seats?status=AVAILABLE \
  -H "Authorization: Bearer <token>"

# 4. Reserve seats (any authenticated role) — low amount, expect it to
#    succeed through the saga
curl -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer <customer-token>" \
  -H "Content-Type: application/json" \
  -d '{"eventId":1,"seatIds":[1,2]}'

# 5. Watch it get confirmed a few seconds later
curl localhost:8080/api/v1/reservations/1 \
  -H "Authorization: Bearer <customer-token>"

# 6. Check the payment record via the circuit-breaker-wrapped route
curl localhost:8080/api/v1/payments/1 \
  -H "Authorization: Bearer <customer-token>"

# 7. Check the simulated notification
curl "localhost:8080/api/v1/notifications?customerId=<id>" \
  -H "Authorization: Bearer <customer-token>"

# 8. To see the compensating path: reserve enough seats that the total
#    exceeds PAYMENT_DECLINE_THRESHOLD_CENTS, then re-check step 3 — the
#    seats should be back to AVAILABLE and the reservation CANCELLED.
```

## Testing

- Unit tests: state transitions, concurrency-conflict handling — no Spring
  context.
- MockMvc integration tests per service.
- Testcontainers integration tests (Postgres + Kafka): the outbox -> Kafka
  -> consumer pipeline, and specifically the compensating-transaction path
  (a `PaymentFailed` event actually releases the held seats).

Run everything: `mvn verify` from the repo root.

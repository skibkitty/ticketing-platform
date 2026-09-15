# Implementation Tasks

Read `docs/architecture.md` first. Work through phases in order — payment-service
can't be meaningfully tested until reservation-service actually publishes
`ReservationCreated`, and notification-service depends on both. Repo
scaffolding (poms, docker-compose, Dockerfiles, CI, package dirs) already
exists — run `mvn -q compile` and fix anything version-related before
writing feature code.

---

## Phase 1 — `common`

- [ ] **T01** — `CorrelationIdFilter`: servlet filter, reads
  `X-Correlation-Id`, generates a UUID if absent, sets SLF4J MDC key
  `correlationId`, sets it on the response, clears MDC after the request.
  Unit test: header echoed when present, generated when absent.
- [ ] **T02** — `EventEnvelope<T>` record: `eventId (UUID), eventType,
  occurredAt (Instant), correlationId, aggregateId, payload (T)`. Unit test:
  Jackson round-trip.
- [ ] **T03** — `ApiErrorResponse` record: `timestamp, status, error,
  message, path, details (List<String>)`.
- [ ] **T04** — `CorsProperties` + `WebMvcConfigurer` reading
  `app.cors.allowed-origins`; only wired up in api-gateway.

---

## Phase 2 — `reservation-service`

- [ ] **T05** — App class + `application.yml` (`DB_URL`, `DB_USER`,
  `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`).
- [ ] **T06** — Flyway `V1__init.sql`: `events` (`id, name, venue,
  event_date`), `seats` (`id, event_id FK, section, row, seat_number,
  status, hold_expires_at NULLABLE, version BIGINT DEFAULT 0`, unique
  constraint on `(event_id, section, row, seat_number)`), `reservations`
  (`id, customer_id, event_id, status, created_at, expires_at`),
  `reservation_seats` (join table: `reservation_id, seat_id`),
  `outbox_events` (id, aggregate_type, aggregate_id, event_type, payload
  JSONB, correlation_id, created_at, published_at NULLABLE),
  `processed_events` (`event_id PK, processed_at`) — this service both
  produces (outbox) and consumes (processed_events) events.
- [ ] **T07** — `Event`, `Seat` (with `@Version`), `Reservation` JPA
  entities + repositories. `SeatStatus` enum (`AVAILABLE, HELD, SOLD`).
  `ReservationStatus` enum (`PENDING_PAYMENT, CONFIRMED, CANCELLED,
  EXPIRED`).
- [ ] **T08** — `EventController` + `EventService`: `POST /api/v1/events`
  (create event + its seats in one request), `GET /api/v1/events`, `GET
  /api/v1/events/{id}/seats` (filterable by status, for browsing
  available seats).
- [ ] **T09** — `ReservationService.createReservation(customerId, eventId,
  seatIds)`: in one `@Transactional` method — load the requested seats,
  verify each is `AVAILABLE`, flip each to `HELD` with `holdExpiresAt =
  now().plusMinutes(10)`, catch `ObjectOptimisticLockingFailureException`
  (or a failed conditional check) and translate it to a `SeatUnavailableException`
  -> 409 if ANY seat can't be held (no partial holds), create the
  `Reservation` (`PENDING_PAYMENT`), write an `OutboxEvent` for
  `ReservationCreated` with payload `{reservationId, customerId, eventId,
  seatIds, amountCents}`.
- [ ] **T10** — `ReservationController`: `POST /api/v1/reservations`, `GET
  /api/v1/reservations/{id}`, `GET /api/v1/reservations?customerId=`.
- [ ] **T11** — `PaymentEventConsumer`: `@KafkaListener` on
  `payment.events.v1`. Idempotency check against `processed_events` first.
  On `PaymentSucceeded`: Reservation -> `CONFIRMED`, its Seats -> `SOLD`,
  write `ReservationConfirmed` outbox event. On `PaymentFailed`: Reservation
  -> `CANCELLED`, its Seats -> `AVAILABLE` (clear `holdExpiresAt`) — this is
  the compensating action — write `ReservationCancelled` outbox event. All
  in one transaction alongside the `processed_events` insert.
- [ ] **T12** — `HoldExpirySweeper`: `@Scheduled` job (e.g. every 60s) that
  finds `PENDING_PAYMENT` reservations with `expires_at` in the past,
  releases their seats to `AVAILABLE`, sets status `EXPIRED`, writes
  `ReservationExpired` outbox event.
- [ ] **T13** — `OutboxPublisher`: same pattern as the earlier CRUD/permit
  projects — `@Scheduled` poller, `KafkaTemplate`, sets the `correlationId`
  Kafka header, marks `published_at`.
- [ ] **T14** — `GlobalExceptionHandler`: `SeatUnavailableException` -> 409,
  `ResourceNotFoundException` -> 404, validation -> 400.
- [ ] **T15** — Unit test: two concurrent `createReservation` calls for the
  *same* seat — simulate the optimistic-lock failure path directly (mock the
  repository to throw on the second save) and assert the second call gets a
  clean 409, not a 500, and holds no seats.
- [ ] **T16** — Testcontainers integration test: create an event with seats,
  `POST /reservations`, assert seats flip to `HELD` and an outbox row
  appears, then assert a `ReservationCreated` message actually lands on
  `reservation.events.v1`.
- [ ] **T17** — Testcontainers integration test: publish a `PaymentFailed`
  message directly to `payment.events.v1` for a reservation set up in
  `PENDING_PAYMENT`; assert the consumer flips it to `CANCELLED` and its
  seats back to `AVAILABLE` — this is the compensating-transaction proof.

---

## Phase 3 — `payment-service`

- [ ] **T18** — App class + `application.yml`, including
  `PAYMENT_DECLINE_THRESHOLD_CENTS`.
- [ ] **T19** — Flyway `V1__init.sql`: `payments` (`id, reservation_id,
  amount_cents, status, processed_at`) + `outbox_events` + `processed_events`.
- [ ] **T20** — `Payment` JPA entity + repository.
- [ ] **T21** — `ReservationEventConsumer`: `@KafkaListener` on
  `reservation.events.v1`, filters for `ReservationCreated`, idempotency
  check, creates a `Payment` row, applies the simulated decision (amount >=
  `PAYMENT_DECLINE_THRESHOLD_CENTS` -> `FAILED`, else `SUCCEEDED`), writes
  the corresponding outbox event (`PaymentSucceeded`/`PaymentFailed`) to
  `payment.events.v1` — all in one transaction plus the processed_events
  insert.
- [ ] **T22** — `OutboxPublisher` (same pattern as T13).
- [ ] **T23** — `PaymentController`: `GET /api/v1/payments/{reservationId}`
  — used by the gateway's circuit-breaker-wrapped status route.
- [ ] **T24** — MockMvc test for the GET route.
- [ ] **T25** — Testcontainers integration test: publish a
  `ReservationCreated` message with a high `amountCents`, assert a
  `PaymentFailed` event is published; repeat with a low amount, assert
  `PaymentSucceeded`.

---

## Phase 4 — `notification-service`

- [ ] **T26** — App class + `application.yml`.
- [ ] **T27** — Flyway `V1__init.sql`: `notifications` (`id,
  recipient_customer_id, type, payload, sent_at`) + `processed_events`.
- [ ] **T28** — `@KafkaListener` on `reservation.events.v1` filtering for
  `ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired` —
  idempotency check, insert a `Notification` row, log a simulated send line.
- [ ] **T29** — `GET /api/v1/notifications?customerId=`.
- [ ] **T30** — MockMvc test + a Testcontainers idempotency test mirroring
  T17's pattern (publish the same event twice, assert one Notification row).

---

## Phase 5 — `api-gateway`

- [ ] **T31** — App + route config: `/api/v1/events/**` and
  `/api/v1/reservations/**` -> reservation-service, `/api/v1/payments/**`
  -> payment-service, `/api/v1/notifications/**` -> notification-service.
  URLs from env vars already defined in docker-compose.yml.
- [ ] **T32** — Correlation-ID filter (forward + log), same pattern as
  `common`'s filter but at the proxy layer.
- [ ] **T33** — Wire up `common`'s CORS config via `CORS_ALLOWED_ORIGINS`.
- [ ] **T34** — JWT auth: `POST /auth/login` against a hardcoded user map
  (`customer/customer` -> `CUSTOMER`, `organizer/organizer` -> `ORGANIZER`,
  `admin/admin` -> `ADMIN`), JJWT-signed token with a `roles` claim, secret
  from `JWT_SECRET`. Gateway filter validates the JWT on `/api/**`
  (excluding `/auth/**`), forwards `X-User-Roles` downstream.
- [ ] **T35** — Role restriction: `POST /api/v1/events` requires `ORGANIZER`
  or `ADMIN`. `POST /api/v1/reservations` requires any authenticated role.
- [ ] **T36** — Resilience4j `RateLimiter` + `Bulkhead` on
  `/api/v1/reservations` (POST only). Configure limits via
  `application.yml`. Exceeding the limit -> 429 with `ApiErrorResponse`.
- [ ] **T37** — Resilience4j `CircuitBreaker` on the
  `/api/v1/payments/**` proxy route with a fallback returning 503 +
  `ApiErrorResponse` on an open circuit.
- [ ] **T38** — Integration test: missing JWT -> 401; `CUSTOMER` role
  hitting `POST /api/v1/events` -> 403; correlation ID generated when
  absent; hammering `POST /api/v1/reservations` past the configured rate
  limit -> 429.

---

## Phase 6 — Observability & finish

- [ ] **T39** — Structured JSON logging with `correlationId` from MDC on
  every log line, every service.
- [ ] **T40** — Actuator `health`/`info` on every service; gateway
  restricts `/actuator/**` to `ADMIN`.
- [ ] **T41** — Re-read `docs/architecture.md` against what was actually
  built and fix any drift (route paths, topic names, env vars, timings).
- [ ] **T42** — `docker compose up --build` end-to-end walkthrough by hand:
  login, create an event with seats, reserve some seats, confirm a
  `ReservationConfirmed` notification appears for a low-amount reservation,
  confirm a `ReservationCancelled` notification and released seats for a
  high-amount (declined) one.
- [ ] **T43** — `mvn verify` at the repo root passes end-to-end (this is
  what CI runs).
- [ ] **T44** — Fill in anything you learned into the six ADR stubs in
  `docs/adr/` — gotchas, version issues, config quirks. Keep them short.
- [ ] **T45** — Write the README's "Example requests" section with real,
  tested curl commands for the full flow (see the stub already there).

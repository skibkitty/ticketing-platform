# Ticketing Platform

An event ticketing & reservation system: seat holds with optimistic
concurrency control, a payment saga with automatic compensation on failure, and
a single authenticated gateway in front of it. Gateway resilience — rate
limiting, a bulkhead and a circuit breaker for the flash-sale reservation route —
is planned in [#13](https://github.com/skibkitty/ticketing-platform/issues/13)
and not built yet.

**Start here:**
1. [`docs/architecture.md`](docs/architecture.md) — what this is and why
   every non-obvious decision was made the way it was.
2. [`docs/adr/`](docs/adr) — short decision records for the main
   choices; fill these in as you build.
3. The [GitHub issue plan](https://github.com/skibkitty/ticketing-platform/issues/1)
   — the spec and its child tickets are the single source of truth for what
   gets built and in what order.

## For opencode / whoever implements this

Scaffolding already exists: parent + module `pom.xml`s, `docker-compose.yml`,
per-module `Dockerfile`s, package directories, CI workflow. Verify `mvn -q
compile` before writing feature code, then work the plan from the GitHub
issues (spec #1 + its child tickets) one ticket at a time, blockers-first —
payment-service's consumer can't be meaningfully tested until
reservation-service actually publishes `ReservationCreated`.

Double check before building: the Spring Cloud release-train version in the
root `pom.xml` against the Spring Boot 3.4.5 compatibility matrix
(https://spring.io/projects/spring-cloud#overview), and the `apache/kafka`
Docker image tag in `docker-compose.yml`.

## Running it

The gateway needs a signing key and the demo callers need passwords, and none
of them is committed — a key or a working password in this repository is a
credential in every clone and every fork. Both are supplied at runtime:

```bash
cp .env.example .env
# fill in the four values; generate the key with: openssl rand -base64 48
docker compose up --build
```

Compose stops with the name of the missing variable rather than substituting
an empty one, and the gateway independently refuses to start without a signing
key of at least 32 bytes or without a password for every configured caller —
so forgetting one is a startup failure, not a deployment signing tokens with a
key nobody chose. `.env` is gitignored; `.env.example` holds placeholders only.

- Gateway: `http://localhost:8080` — the only published port in the default
  stack, on every interface

The services behind the gateway, and the infrastructure they run on, publish
nothing to the host. The gateway is where a token is checked and a role is
enforced, and the services behind it trust the identity headers it sets rather
than checking anything themselves, so a published port would be a way around
both (see [`docs/adr/012`](docs/adr/012-gateway-is-the-only-published-surface.md)).
The database is the widest read of all — every service's schema is in it, behind
credentials that are in the compose file — and the Kafka UI shows the whole event
history while authenticating nobody, so they are held to the same rule
([`docs/adr/013`](docs/adr/013-infrastructure-ports-follow-the-gateway-rule.md)).

To debug a service directly — `curl localhost:8084/actuator/health`, an IDE
attached to the process — or to reach the infrastructure — `psql`, a Kafka CLI,
the Kafka UI at `http://localhost:8090` — ask for it explicitly:

```bash
docker compose -f docker-compose.yml -f docker-compose.debug.yml up --build
```

That publishes reservation-service `:8082`, payment-service `:8083`,
notification-service `:8084`, postgres `:5432`, kafka `:29092` and the Kafka UI
`:8090` on `127.0.0.1`, for as long as that stack is up. A port bound to
loopback is reachable by any process on this machine, which includes anything
that can skip the gateway's authentication and read the database, so it is for
debugging and not for anything else.

Note that a service run on the host with `mvn spring-boot:run` needs this
override: every service's `application.yml` defaults to `localhost:5432` and
`localhost:29092`, so against the default stack it will find nothing listening.
Inside the compose stack nothing does — the services reach the database as
`postgres:5432` and the broker as `kafka:9092`, over the compose network.

## Authentication is a demo credential set

The login surface is three configured callers — `customer`, `organizer` and
`admin` — whose passwords come from `.env` and are compared against the values
in `application.yml`. It is not an identity provider, and it is not
production-grade password authentication. Specifically:

- **No password store.** The configured value *is* the credential. There is no
  password database, no hashing at rest, and no user-facing registration.
- **A password change revokes nothing.** It changes what `/auth/login` accepts
  from then on. A token issued before the change stays valid until it expires.
- **A token is valid until its expiry** (`JWT_TTL`, one hour by default) and
  there is no refresh token, so "logging out" means "stop sending it".
- **Failed logins are counted, per process.** `/auth/login` refuses a caller over
  a limit on two keys at once — five failures per account, twenty per remote
  address, in a five-minute window — and answers `429` with a `Retry-After`. A
  correct password is refused exactly like a wrong one while the limit is spent, so
  the endpoint does not reveal *which* credentials exist any more than it did
  before, and now says nothing about how many attempts it will answer. Two limits
  it does not overcome: callers behind one NAT share the address budget, and
  counters live in each gateway, so N replicas behind a load balancer mean N times
  the budget. The flash-sale rate limiting in
  [#13](https://github.com/skibkitty/ticketing-platform/issues/13) is a different
  route and does not cover this.
- **Roles are configuration**, not something a user holds or changes.

[`docs/adr/002`](docs/adr/002-gateway-trust-boundary.md) records the trust
boundary these credentials sit behind, and what it costs;
[`docs/adr/015`](docs/adr/015-gateway-login-abuse-prevention.md) records the
login limiter and the properties it does not have.

## Example requests

*(To be filled in with real, tested commands as part of T14 (#15).)*

The demo logins are `customer`, `organizer` and `admin`, with the passwords you
set in `.env`. A token's subject is the caller's own id — `42` for `customer` —
and for a caller that is a Customer that id is its `Customer.id`, which is the
only case the gateway puts `X-Customer-Id` on the request it forwards. An
organizer and an admin have ids too, but holding those roles is not being a
Customer, so they get no such header and a value you set by hand is overwritten
or dropped either way.

Reservations are the Customer's alone: `POST /api/v1/reservations` answers an
organizer with 403, because a Reservation belongs to a Customer and there is no
Customer id to book an organizer's against. Browsing Events stays open to any
authenticated caller, since a Customer has to be able to look before buying.

`GET /api/v1/notifications` is the Customer's own inbox and takes no customer id
at all — it reads the one the gateway verified, so a `?customerId=` on it is
either redundant or refused. An operator reads someone else's inbox on a separate,
`ADMIN`-only route, kept separate so the self-service read can never be widened:
`GET /api/v1/admin/customers/<id>/notifications`. See
`docs/adr/011-notification-read-ownership.md`.

Reservations are read the same way, by the same rule. `GET
/api/v1/reservations/<id>` and `GET /api/v1/reservations` are scoped by that same
verified header and by nothing else: the path or query picks *which* reservation,
and the header says *whose*. Someone else's reservation is a 404 — the same answer
as an id that was never issued, so the response does not confirm it exists — and a
`?customerId=` naming somebody else is refused with 400. Both reads need the
gateway's header, so neither is reachable without it. See
`docs/adr/014-reservation-read-ownership.md`.

```bash
# 1. Log in
curl -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"organizer","password":"<your DEMO_ORGANIZER_PASSWORD>"}'

# 2. Create an event with seats (ORGANIZER/ADMIN token)
curl -X POST localhost:8080/api/v1/events \
  -H "Authorization: Bearer <organizer-token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Radiohead","venue":"The Fillmore","eventDate":"2026-11-01T20:00:00Z","seats":[{"section":"A","row":1,"seatNumber":1,"priceCents":15000},{"section":"A","row":1,"seatNumber":2,"priceCents":15000}]}'

# 3. Browse available seats
curl localhost:8080/api/v1/events/1/seats?status=AVAILABLE \
  -H "Authorization: Bearer <token>"

# 4. Reserve seats (CUSTOMER token) — low amount, expect it to
#    succeed through the saga
curl -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer <customer-token>" \
  -H "Content-Type: application/json" \
  -d '{"eventId":1,"seatIds":[1,2]}'

# 5. Watch it get confirmed a few seconds later. This read is scoped by
#    the customer id the gateway derived from the token, so a Customer
#    gets 404 for anyone's reservation but their own. No header is
#    needed — the gateway supplies it — and asking for someone else's
#    with ?customerId= is refused with 400.
curl localhost:8080/api/v1/reservations/1 \
  -H "Authorization: Bearer <customer-token>"

# 5b. List the caller's own reservations
curl localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer <customer-token>"

# 6. Check the payment record. The circuit breaker on this route is planned
#    in #13 and not built yet; today it is a plain proxied read
curl localhost:8080/api/v1/payments/1 \
  -H "Authorization: Bearer <customer-token>"

# 7. Check the simulated notification — the inbox is the caller's own,
#    scoped by the id the gateway verified, not by anything in the URL
curl localhost:8080/api/v1/notifications \
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
- `platform-tests`: the deployment rather than any one service — chiefly that no
  internal service publishes a port, which is what makes the gateway the trust
  boundary. It reads the compose files and, where Docker is installed, asks
  `docker compose config` for the model the containers would start from.

Run everything: `mvn verify` from the repo root.

# Ticketing Platform

An event ticketing & reservation system: seat holds with optimistic
concurrency control, a payment saga with automatic compensation on failure, and
a single authenticated gateway in front of it, with a rate limit and a bulkhead
on the flash-sale reservation route and a circuit breaker on the payment-status
route.

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
  the budget. The flash-sale rate limit on `POST /api/v1/reservations` is a
  different mechanism on a different route and does not cover this.
- **Roles are configuration**, not something a user holds or changes.

[`docs/adr/002`](docs/adr/002-gateway-trust-boundary.md) records the trust
boundary these credentials sit behind, and what it costs;
[`docs/adr/015`](docs/adr/015-gateway-login-abuse-prevention.md) records the
login limiter and the properties it does not have.

## Example requests

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

Payments follow the same rule. `GET /api/v1/payments/<reservationId>` returns the
payment behind one of the caller's own reservations. Someone else's reservation
id gets the same 404 as one that was never issued. It is Customer-only at the
gateway. See `docs/adr/016-payment-read-ownership.md`.

### The walkthrough

Every command below was run against `docker compose up --build`, in order, on an
empty database; the ids in the expected output are what a fresh stack hands out,
so on a stack that has seen earlier runs use the ids your own responses return.
To watch a Hold lapse without waiting ten minutes, start the stack with a short
one:

```bash
HOLD_DURATION=PT30S docker compose up --build -d
until curl -s -o /dev/null localhost:8080/auth/login; do sleep 2; done   # the gateway is listening
```

The knobs the walkthrough leans on, both read by `docker-compose.yml` from the
shell or `.env`:

| Variable | Default | What it decides |
| --- | --- | --- |
| `HOLD_DURATION` | `PT10M` | how long a Reservation holds its Seats before the sweep may expire it |
| `PAYMENT_DECLINE_THRESHOLD_CENTS` | `50000` | the simulated payment declines any Reservation whose total is **this or more**, and succeeds below it |

#### Log in

```bash
set -a; . ./.env; set +a   # the demo passwords, into this shell only

ORG=$(curl -s -X POST localhost:8080/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"organizer\",\"password\":\"$DEMO_ORGANIZER_PASSWORD\"}" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')
CUS=$(curl -s -X POST localhost:8080/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"customer\",\"password\":\"$DEMO_CUSTOMER_PASSWORD\"}" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

# The gateway is up before the services behind it, and a hop to one that is
# still starting answers 500. Wait until all three answer:
until curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/events -H "Authorization: Bearer $ORG" | grep -q 200 \
   && curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/payments/999999 -H "Authorization: Bearer $CUS" | grep -q 404 \
   && curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/notifications -H "Authorization: Bearer $CUS" | grep -q 200
do sleep 2; done
```

A login answers `{"token":"…","roles":["ORGANIZER"],"expiresAt":"…"}`; the
token is good for an hour (`JWT_TTL`). On a cold start the services take a
minute or more to come up after the gateway; the loop above is what tells you
they have.

#### Create an Event with Seats (Organizer)

Two cheap Seats for the success path, two dear ones for the decline, and one for
the expiry.

```bash
curl -s -X POST localhost:8080/api/v1/events \
  -H "Authorization: Bearer $ORG" -H "Content-Type: application/json" \
  -d '{"name":"Radiohead","venue":"The Fillmore","eventDate":"2026-11-01T20:00:00Z","seats":[
        {"section":"A","row":"1","seatNumber":1,"priceCents":15000},
        {"section":"A","row":"1","seatNumber":2,"priceCents":15000},
        {"section":"B","row":"1","seatNumber":1,"priceCents":30000},
        {"section":"B","row":"1","seatNumber":2,"priceCents":30000},
        {"section":"C","row":"1","seatNumber":1,"priceCents":5000}]}'
# 201 {"eventId":1,"seatIds":[1,2,3,4,5]}

curl -s "localhost:8080/api/v1/events/1/seats?status=AVAILABLE" -H "Authorization: Bearer $CUS"
# 200 — all five Seats, each "status":"AVAILABLE"
```

#### 1. Payment succeeds: CONFIRMED, Seats SOLD

Seats 1 and 2 total 30000 cents, under the decline threshold.

```bash
curl -s -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $CUS" -H "Content-Type: application/json" \
  -d '{"eventId":1,"seatIds":[1,2]}'
# 201 {"id":1,"customerId":42,"status":"PENDING_PAYMENT","amountCents":30000,
#      "expiresAt":"…",  "seats":[… "status":"HELD" …]}

# A few seconds later, once the saga has run:
curl -s localhost:8080/api/v1/reservations/1 -H "Authorization: Bearer $CUS"
# 200 {"id":1,"status":"CONFIRMED", … "seats":[… "status":"SOLD" …]}

curl -s localhost:8080/api/v1/payments/1 -H "Authorization: Bearer $CUS"
# 200 {"reservationId":1,"amountCents":30000,"status":"SUCCEEDED","settledAt":"…"}

curl -s localhost:8080/api/v1/notifications -H "Authorization: Bearer $CUS"
# 200 {"items":[{"reservationId":1,"type":"RESERVATION_CONFIRMED",
#      "message":"Your reservation 1 is confirmed. Seats 1, 2 are yours.", …}],"nextCursor":null}
# (the Seats are listed in no particular order)
```

#### 2. Payment declined: CANCELLED, Seats released

Seats 3 and 4 total 60000 cents, over the threshold, so the payment fails and
reservation-service compensates.

```bash
curl -s -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $CUS" -H "Content-Type: application/json" \
  -d '{"eventId":1,"seatIds":[3,4]}'
# 201 {"id":2,"status":"PENDING_PAYMENT","amountCents":60000, …}

# A few seconds later:
curl -s localhost:8080/api/v1/reservations/2 -H "Authorization: Bearer $CUS"
# 200 {"id":2,"status":"CANCELLED", … "seats":[… "status":"AVAILABLE" …]}

curl -s localhost:8080/api/v1/payments/2 -H "Authorization: Bearer $CUS"
# 200 {"reservationId":2,"amountCents":60000,"status":"FAILED", …}

curl -s "localhost:8080/api/v1/events/1/seats?status=AVAILABLE" -H "Authorization: Bearer $CUS"
# 200 — Seats 3, 4 and 5: the declined pair is back on sale

curl -s localhost:8080/api/v1/notifications -H "Authorization: Bearer $CUS"
# newest item: "type":"RESERVATION_CANCELLED",
#   "message":"Your reservation 2 was cancelled because the payment was declined. Seats 3, 4 have been released."
```

Every payment settles within seconds, so the only way to make one fail on demand
is the threshold: reserve at least `PAYMENT_DECLINE_THRESHOLD_CENTS` worth of
Seats, or start the stack with a lower one (`PAYMENT_DECLINE_THRESHOLD_CENTS=0`
declines every payment).

#### 3. The Hold lapses: EXPIRED, Seats released

A Hold only lapses if no payment outcome arrives first, and the simulated payment
always answers within seconds. Stopping payment-service stands in for a customer
who never completes checkout:

```bash
docker compose stop payment-service

curl -s -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $CUS" -H "Content-Type: application/json" \
  -d '{"eventId":1,"seatIds":[5]}'
# 201 {"id":3,"status":"PENDING_PAYMENT","expiresAt":"<now + HOLD_DURATION>", …}

# Once expiresAt has passed (about 30 s with HOLD_DURATION=PT30S), the sweep
# releases the Seat on its own:
curl -s "localhost:8080/api/v1/events/1/seats?status=AVAILABLE" -H "Authorization: Bearer $CUS"
# 200 — Seats 3, 4 and 5

curl -s localhost:8080/api/v1/reservations/3 -H "Authorization: Bearer $CUS"
# 200 {"id":3,"status":"EXPIRED", … "seats":[… "status":"AVAILABLE" …]}

curl -s localhost:8080/api/v1/notifications -H "Authorization: Bearer $CUS"
# newest item: "type":"RESERVATION_EXPIRED",
#   "message":"Your reservation 3 expired before payment completed. Seat 5 has been released."
```

Bring payment-service back and it settles the abandoned Reservation late. The
outcome is ignored ([ADR 007](docs/adr/007-payment-outcome-guard.md)): the
Reservation stays `EXPIRED`, its Seat stays on sale, and no second notification
is sent, even though the payment itself reads `SUCCEEDED`.

```bash
docker compose start payment-service

# Once it has caught up (around half a minute while it starts):
curl -s localhost:8080/api/v1/payments/3 -H "Authorization: Bearer $CUS"
# 200 {"reservationId":3,"amountCents":5000,"status":"SUCCEEDED", …}

curl -s localhost:8080/api/v1/reservations/3 -H "Authorization: Bearer $CUS"
# 200 {"id":3,"status":"EXPIRED", …}
```

#### Following one request through the logs

Every response carries the `X-Correlation-Id` the gateway decided on, and every
service logs it as `correlationId` on each line written for that request, Kafka
hops included. Add `-D -` to a reservation's `POST` to see its id, then:

```bash
docker compose logs --no-log-prefix api-gateway reservation-service payment-service notification-service \
  | grep '"correlationId":"<that id>"'
```

For the reservation in flow 1 that is the gateway's `POST /api/v1/reservations -> 201`,
`Reservation 1 placed`, `Payment for reservation 1 SUCCEEDED`, `Reservation 1
confirmed`, and notification-service's `Simulated send: RESERVATION_CONFIRMED`.

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
- The seat-contention load test, in `platform-tests` but run only under
  `-Pload-test`: 200 concurrent buyers race for 5 Seats through the gateway,
  and no Seat is ever sold twice. Results, hardware and the rerun command are
  in [`docs/load-test.md`](docs/load-test.md).

Run everything: `mvn verify` from the repo root. It needs Docker for the
Testcontainers suites, and it starts its own Postgres and Kafka, so stop the
compose stack first on a small machine: the two together outgrow a few-GB WSL
VM, and Docker stops answering rather than failing cleanly.

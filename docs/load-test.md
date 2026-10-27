# Seat-contention load test

Many buyers, few Seats, and one question: does any Seat get sold or held
twice? ADR 006 says optimistic locking on `Seat` (`@Version`) means it cannot.
This test is the evidence.

**Result: 200 concurrent buyers racing for 5 Seats through the gateway, zero
oversells, in every run.** With the `@Version` column removed, the same test
sold the 5 Seats to 13 Reservations and failed, so it catches the failure it
exists to rule out.

## What it does

`platform-tests/src/test/java/com/raydans/platform/SeatContentionLoadTest.java`,
against a compose stack that is already up:

1. Logs in as `organizer` and creates a fresh Event with 5 Seats at 5000 cents
   each. That is well under the decline threshold, so every winner's payment
   succeeds. A fresh Event per run means it reruns on a stack that has seen
   earlier runs.
2. Warms the route up with a few reads, so the first buyers don't also pay for
   a cold JVM.
3. Starts 200 buyers on their own threads, holds them at a gate until all are
   ready, then releases them at once. Each sends `POST /api/v1/reservations`
   through the gateway. Even-numbered buyers ask for one Seat, odd-numbered for
   an adjacent pair, round the 5 Seats. Pairs overlap their neighbours, and a
   Reservation is all or nothing.
4. Counts every status separately: `201`, `409`, `429`, any other status, and
   "no response" for a connection that failed.
5. Checks that **every buyer got a 201 or a 409**, and nothing else. The test
   fails on anything else and gives the counts by status and example bodies:
   - A 429 means the limiter, not the database, decided who lost, so the run
     didn't measure the race. The message says which variables to raise.
   - A 5xx or no response means a buyer whose outcome nobody knows, which a
     clean end state would otherwise hide.
6. Waits up to 2 minutes for the saga to settle (no Reservation still
   `PENDING_PAYMENT`). If it doesn't settle, the test fails and names the
   Reservations still pending, because checking the state before the saga
   finishes would verify a moment, not the outcome. Then it checks:
   - **Every 201 is a real Reservation, and nothing else made one.** The
     Event's Reservations are exactly the ids the 201s returned, so no 409 (or
     anything else) left a Reservation or a held Seat behind.
   - **No Seat in more than one live Reservation** (`PENDING_PAYMENT` or
     `CONFIRMED`), as the API reports them.
   - **The `HELD`/`SOLD` Seats are exactly the Seats of live Reservations,** as
     the Seats' own statuses report them.
   - **The same question asked of the database,** with `psql` inside the
     postgres container, because the API could in principle misreport it.
     Postgres publishes no port (ADR 013), so the test asks with
     `docker compose exec`.

**Single demo Customer.** The gateway has one Customer login (`customer`,
id 42), so all 200 buyers book as the same Customer. They are still separate
Reservations racing for the same Seat rows, so this tests seat contention, not
fairness between Customers or any per-Customer limit.

## Results

Each failure path was also run on purpose, once, to show it fires:

| Set-up | Result |
| --- | --- |
| Gateway at its default limits | failed: `By status: {429=100}`, with example bodies |
| payment-service stopped | failed: `The saga did not settle within PT2M: Reservations [22, 17, 15] are still PENDING_PAYMENT` |
| `@Version` removed from `SeatEntity` | failed: 13 Reservations for 5 Seats, one Seat in 7 of them |

Runs before the stricter status and settle checks were added, all on
2026-10-26, against the same stack, each on a fresh Event:

| Run | Code | 201 | 409 | 429 | Other | Oversells | Throughput | p50 | p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 (first after start) | as built | 5 | 195 | 0 | 0 | **0** | 75 req/s | 2127 ms | 2584 ms |
| 2 | as built | 4 | 196 | 0 | 0 | **0** | 109 req/s | 896 ms | 1717 ms |
| 3 | as built | 3 | 197 | 0 | 0 | **0** | 150 req/s | 851 ms | 1111 ms |
| 4 | as built | 4 | 196 | 0 | 0 | **0** | — | — | — |
| 5 | `@Version` removed | 13 | 187 | 0 | 0 | **every Seat, 3–5×** (test failed) | — | — | — |
| 6 | as built, after revert | 5 | 195 | 0 | 0 | **0** | 133 req/s | 1062 ms | 1322 ms |

Run 4's timings weren't recorded. Run 5 was the check that the test can fail;
its timings aren't comparable.

With the stricter checks, on a fresh stack:

| Run | Code | 201 | 409 | Other | Oversells | Throughput | p50 | p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 (first after start) | as built | 4 | 196 | 0 | **0** | 78 req/s | 1917 ms | 2414 ms |
| 2 (after the `@Version` check, reverted) | as built | 4 | 196 | 0 | **0** | 128 req/s | 1172 ms | 1379 ms |

Winners vary between 3 and 5 because a pair can win, and a winning pair takes
two of the five Seats. Every run ended with every Seat sold exactly once or not
at all.

### Read the speed numbers as one laptop, not the system

- **Machine:** AMD Ryzen 5 7535HS (6 cores, 12 threads), Windows with WSL 2
- **WSL VM:** 3.4 GB RAM, 1 GB swap. Swap was full during the runs.
- **Docker:** Docker Desktop 29.6.2, 12 CPUs, 3.4 GB
- **Stack:** the full `docker-compose.yml`, all on that VM: four Spring Boot
  services, Postgres, Kafka, kafka-ui. The test client also ran on the same
  machine, so it competed with the stack for CPU.
- **Shape of the load:** one burst of 200 requests released at once, not a
  sustained rate. Latency is mostly queueing: reservation-service holds at
  most 10 database connections at once (Hikari's default pool), so 200
  transactions on the same 5 rows wait their turn.

The first run after a cold start is the slowest even with the warm-up, because
the JVMs are still compiling the hot path.

## Rerun it

Don't run this alongside `mvn verify`: on a small WSL VM the stack plus the
Testcontainers suites exhaust memory, and Docker stops answering instead of
failing.

```bash
# 1. The stack, with the flash-sale limits raised for this run only. At the
#    defaults (100 per minute, 20 in flight) most buyers get 429 and the test
#    fails, because it would be measuring the limiter instead of the race.
#    The connection pool must be at least the bulkhead, or the gateway refuses
#    to start.
RESERVATION_RATE_LIMIT=10000 \
RESERVATION_BULKHEAD_MAX_CONCURRENT=200 \
GATEWAY_MAX_CONNECTIONS_PER_SERVICE=200 \
docker compose up --build -d

# 2. The test. It waits for the services to answer through the gateway, and
#    reads the demo passwords from .env (or the environment).
mvn -pl platform-tests -Pload-test test
```

The report prints to the console. `-DloadTest.buyers=…`, `-DloadTest.seats=…`
and `-DloadTest.baseUrl=…` change the run's shape and target.

If the stack runs under a non-default compose project name, export
`COMPOSE_PROJECT_NAME` for the `mvn` command as well, so the database check
reaches the same postgres container.

Plain `mvn verify` neither runs this test nor needs a stack: it is tagged
`load-test` and excluded unless `-Pload-test` is given.

To repeat the "can it fail" check, comment out `@Version` on
`SeatEntity.version`, rebuild reservation-service
(`docker compose up --build -d reservation-service` with the same three
variables set), and rerun. Expect a red run with Seats in several live
Reservations, then revert.

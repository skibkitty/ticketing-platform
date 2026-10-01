# ADR 013: The infrastructure ports follow the same rule as the application ports

**Status:** Accepted

**Context:** ADR 012 removed the three internal services' host ports and left
`docker-compose.yml` publishing the gateway's port plus three infrastructure
ones, on the reasoning that they were "infrastructure rather than anything in the
request path". It recorded that reasoning as a cost worth naming separately
rather than as a settled decision:

> This does not change what a host-local process can do to the *database*, which
> is still published on every interface for the same debugging reasons and is
> worth a separate decision.

This is that decision. The justification does not survive contact with what
these three services actually are:

- **`postgres` holds everything.** All four schemas — reservation, payment,
  notification and the platform's own — are in one database, and the credentials
  are in the compose file, because they have to be for the application services
  to boot. A published `5432` is a socket that reads every Customer's
  reservations, payments and notifications. It is a *wider* read than the
  application ports ADR 012 removed, not a narrower one: ADR 002's headers gate a
  request the gateway is looking at, and the database has no gateway in front of
  it at all.
- **`kafka-ui` shows the whole saga.** Every topic — `reservation.events.v1`,
  `payment.events.v1` — with every Customer's reservation and payment history,
  and it authenticates nobody. A published `8090` is that read with no
  credential at all.
- **`kafka` is a broker.** Publishing it invites a client to produce into the
  topics the platform's outbox and consumers depend on.

The "debugging reasons" were real: every service's `application.yml` defaults to
`localhost:5432` and `localhost:29092`, so a developer running one with `mvn
spring-boot:run` against the stack needs both, and the Kafka UI is genuinely
useful. Keeping the ports published in the *default* file is not how you solve
that — it is how you make the common case the unsafe one, and the safe case the
one you have to remember a flag for.

There is also no deployment other than this file. `docker compose up` is how the
platform is run, so "infrastructure, not a deployment concern" was not true of
anything.

**Decision:** `docker-compose.yml` publishes exactly one port, the gateway's, and
nothing else. The three infrastructure services join the three application
services in reaching the host by no route at all, and host access to them moves
into `docker-compose.debug.yml` alongside the application ports, bound to
`127.0.0.1` for the same reason and on the same terms (ADR 012): a debugging
convenience is not a control.

Service-to-service communication is unaffected and was never at risk: the
application services reach `postgres:5432` and `kafka:9092` by service name over
the compose network, and the gateway reaches the services by service name. The
`KAFKA_ADVERTISED_LISTENERS` `PLAINTEXT_HOST` entry stays, because it is the
advertised listener for the override's host port rather than a published port by
itself, and removing it would break the override.

**Consequences:** The default stack is a closed network with one door in it, so
"the gateway is the only published surface" is now true of the file as a whole
rather than only of the application services in it.

The cost is the one ADR 012 already accepted, extended to the ports nobody had
yet accepted losing it. A developer who wants `psql localhost:5432` or the Kafka
UI at `localhost:8090` now passes the second `-f` file, and a developer running a
service on the host against the default stack gets connection-refused — the same
answer ADR 012 gives for `localhost:8082-8084`, and the right one, because with
nothing published there is nothing to connect to. The override is the documented
way to get both, and it is one flag in the command that starts the stack.

`InternalServiceExposureTests` and `ComposeResolutionTests` hold the line, and
the second asks `docker compose config` rather than reading the file, so what is
asserted is the model the containers start from. The published-port allowlist is
now a single entry, so "the default stack publishes one port, and it is the
gateway's" is a fact a reader of the test can check at a glance, and a second
published port anywhere — on any interface — fails the build. The loopback
assertion covers the infrastructure too, which is where a debug override is most
likely to be written loosely: a developer adding `- "5432:5432"` for a `psql`
session is editing the one file that says not to.

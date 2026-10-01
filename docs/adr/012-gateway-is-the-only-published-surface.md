# ADR 012: The gateway is the only published HTTP surface

**Status:** Accepted

**Context:** ADR 002 makes the API Gateway the place where a JWT is verified, a
role is checked, and the `X-Customer-Id` a downstream service acts on is
derived — and every service behind it trusts that header without an
authentication of its own. That is only true if a request cannot arrive without
passing through the gateway, so the network is part of the decision, not a
detail of how it is deployed.

The default `docker-compose.yml` published the three internal services on host
ports, each bound to `127.0.0.1`:

```yaml
ports:
  - "127.0.0.1:8084:8080"
```

That reads as "internal" and is treated as such by everyone reading it. It is
also a socket any process on the machine can open, and ADR 011's operator route
— `GET /api/v1/admin/customers/{id}/notifications` — is guarded by `ADMIN` at the
gateway and by nothing inside notification-service. A host-local caller skips
the gateway, skips the role check, names any Customer, and reads their
Notifications. Nothing in the service notices, because the service was
deliberately built to notice nothing: ADR 002 declines to put an authorization
decision in a service that holds no authentication, and ADR 011 declines to put
one on the self-service route. The gateway is the whole of the answer, and a
published port is a way around the answer.

Loopback was better than `0.0.0.0` and was described as such. It keeps a
colleague on the LAN out, and it does not keep anything else out.

**Decision:** No internal service publishes a port. The default stack publishes
one application port — the gateway's, on every interface. The internal services
are reachable by Docker service name over the compose network
(`http://notification-service:8080`), which is how the gateway reaches them, and
from nowhere else. The infrastructure ports, which this ADR left published and
recorded as a separate decision, are unpublished too: see ADR 013.

Host-side debugging is a separate, opt-in file:

```bash
docker compose -f docker-compose.yml -f docker-compose.debug.yml up --build
```

`docker compose up` reads only `docker-compose.yml`, so the default path cannot
publish an internal port even by accident, and the file that can says in its
first paragraph what a published port is worth. The bindings there are
loopback-only: a debugging convenience is not a control, and the difference
between `127.0.0.1` and `0.0.0.0` is the difference between "a process on this
machine" and "anything on this network".

`InternalServiceExposureTests` and `ComposeResolutionTests` in the
`platform-tests` module hold the line, and the second of them asks `docker
compose config` rather than reading the file, so what is asserted is the model
the containers start from. The list of published ports is an allowlist rather
than a rule naming the three current services, so the next service added has to
make the same decision deliberately.

**Consequences:** A developer who wants `curl localhost:8084` has to say so, and
the trace of who said it is in the command they ran. A developer running the
gateway on the host against the default stack will get connection-refused
instead, which is the correct answer: with no internal port published there is
nothing for it to connect to, and the gateway's `localhost:808x` route defaults
apply to running every service on the host, not to a half-and-half setup.

The cost is real and worth naming: on a developer's own machine, a published
loopback port was enough to keep the service reachable for a debugger, a
profiler, an HTTP client, or a `kubectl port-forward` habit. That is now one
flag away rather than always on, and a developer debugging notification-service
has to remember the flag. What is not acceptable is trading that away for a
boundary nobody is enforcing, so the port stays off by default.

This does not change what a host-local process can do to the *database*, which
is still published on every interface for the same debugging reasons. That is a
separate decision, and ADR 013 makes it: the infrastructure ports are unpublished
in the default file too, and move to the same debug override.

**An `internal: true` network was considered and rejected.** It is the usual
suggestion for this problem and it is worth saying why it is not the answer here,
because the reason is not that it is unsafe — it is that it guards a different
thing. Marking the compose network `internal: true` removes *outbound* connectivity
for everything on it: no image pulls from a registry, no calls out to a payment
provider, no egress at all. That is a containment property, and the exposure
vector being guarded here is *inbound* — a socket published to the host, which an
internal network does not unpublish. Setting it would have left the port
assertions unchanged and their intent unimplemented, while breaking the one thing
these services legitimately need: the payment-service talking to a provider.

The two are not equally valuable anyway. Outbound egress is not what makes an
internal service unsafe to expose, and it is not where this platform's data
leaks — an internal service that trusts `X-Customer-Id` (ADR 002) is unsafe
because of who can *reach* it and write that header, which is exactly what
`InternalServiceExposureTests` asserts and what an internal network would leave
alone. `internal: true` was therefore declined as solving a problem this ADR does
not have, at the cost of one it does.

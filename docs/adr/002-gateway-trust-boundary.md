# ADR 002: Gateway-only JWT validation, downstream services trust a header

**Status:** Accepted

**Context:** Every request into the platform needs auth. Each service could
validate its own JWT, or one place could do it for all of them.

**Decision:** The gateway validates the JWT and forwards `X-User-Roles`
downstream; services trust that header rather than re-validating a
signature.

**Consequences:** One place to audit and rotate keys; downstream services
carry no security dependency. The safety of trusting `X-User-Roles` rests
on network hygiene, not protocol: nothing except our own services may reach
an internal service. The default `docker-compose.yml` publishes host ports
(8082-8084) for direct debugging, so a host-local process *can* forge the
header today — accepted at demo scale. Tighten by removing the host port
bindings for internal services once host-side debugging is done. If this
ever runs on a shared or untrusted network, each service must validate the
JWT itself instead of trusting the header.

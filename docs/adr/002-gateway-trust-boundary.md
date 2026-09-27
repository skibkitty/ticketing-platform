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
an internal service. The default `docker-compose.yml` binds the internal
services' host ports (8082-8084) to `127.0.0.1` rather than every interface,
so host-side debugging still works while the rest of the network cannot reach
them at all; a host-local process can still forge the header, which is
accepted at demo scale. A host port is a debugging convenience, never an
access path: an internal service with no gateway route in front of it is not
safe to expose, and one that reads a Customer's data by an id in the query
string has nothing of its own to stop that. Remove the loopback bindings too
once host-side debugging is done. If this ever runs on a shared or untrusted
network, each service must validate the JWT itself instead of trusting the
header, and the gateway must derive the Customer id in the request from the
token's subject rather than pass a caller-supplied one through.

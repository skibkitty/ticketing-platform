# ADR 002: Gateway-only JWT validation, downstream services trust a header

**Status:** Accepted

**Context:** Every request into the platform needs auth. Each service could
validate its own JWT, or one place could do it for all of them.

**Decision:** The gateway validates the JWT and forwards `X-User-Roles`
downstream; services trust that header rather than re-validating a
signature.

**Consequences:** One place to audit and rotate keys; downstream services
carry no security dependency. This is only safe because internal services
are not reachable except through the gateway — in an environment with
untrusted internal network access (e.g. a shared cluster), each service
would need to validate the JWT itself instead of trusting the header.

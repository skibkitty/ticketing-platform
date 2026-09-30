# ADR 002: Gateway-only JWT validation, identity derived from the token

**Status:** Accepted

**Context:** Every request into the platform needs auth. Each service could
validate its own JWT, or one place could do it for all of them. A downstream
service also has to know *which* Customer is asking — `POST
/api/v1/reservations` books seats against a `customerId` — and that id has to
come from somewhere the caller cannot choose.

**Decision:** The gateway validates the JWT and forwards two headers
downstream, `X-User-Roles` and `X-Customer-Id`; services trust those headers
rather than re-validating a signature. Both values are derived from the
verified token and are never taken from the request the client sent.

**The `sub` claim is the caller's own id, and only a Customer's id is
published.** A caller is whatever authenticated and holds roles — a Customer,
an Event's organizer, the platform's operator. Each has an immutable numeric id,
and that id is the token's subject. Not the username, not the login name, not
anything else a client chose.

Holding the `CUSTOMER` role is what makes a caller a `Customer`; the domain
models one identity, referenced by identifier only, and organizer and admin are
capabilities rather than things separately recorded (CONTEXT.md). So the two
facts are separate, and the gateway keeps them separate: a caller's id is its
`sub`, and the same number is a `Customer.id` — and is forwarded as
`X-Customer-Id` — only when the caller is a Customer.

```
Login
  ↓
JWT:  sub = caller id      roles = [...]     (organizer: 43, roles [ORGANIZER])
  ↓
API Gateway validates the JWT signature and expiry
  ↓
Gateway derives X-Customer-Id from the verified sub
  ↓                    ... but only when roles include CUSTOMER
reservation-service
```

The flow matters more than the spelling. The id a downstream service acts on
is the one the gateway read out of a signature it checked, so a caller cannot
act as a Customer it did not authenticate as.

**An organizer is not a Customer, and is not given one.** It authenticates, it
has an id, and it creates Events; it gets no `X-Customer-Id`, because there is no
Customer id to give it. A Reservation belongs to a Customer by definition and
`reservation-service` cannot record one without that header, so the gateway
answers an organizer's `POST /api/v1/reservations` with 403 rather than inventing
a Customer to satisfy the request. Were organizers ever meant to buy seats, the
answer is a Customer identity for them in the domain — a modelling change, not a
header the gateway may fabricate.

**The gateway never accepts `X-Customer-Id` from the client.** Any inbound
`X-Customer-Id` is removed before the request is forwarded, unconditionally,
and the outbound value is set from the verified subject, only for a caller that
is a Customer. Specifically:

- The id is **not** derived from the username, and a token whose subject is not a
  valid caller id is refused rather than guessed at.
- There is **no hardcoded username → id mapping** in code. Which login names
  belong to which identity is data the gateway is configured with, co-located
  with those callers' credentials, and a real deployment replaces that with an
  identity store read at login.
- There is **no parsing or guessing** of a username into an id.
- The configuration key is `caller-id`, not `customer-id`, and that is not
  cosmetic: every caller needs an id because every token's subject is one, and
  naming the field after a role would assert that every caller is a Customer.
- A `caller-id` belongs to **one** caller, and the gateway refuses to start if
  two configured callers claim the same one. Sharing an id does not make a
  caller unusable, it makes two of them interchangeable: both would be issued a
  token with the same subject, every service downstream acts on that number, and
  one Customer's data turns up under another. The gateway will not break the tie
  by choosing, because it has no way to know which of the two was configured on
  purpose.

The same rule already applied to `X-User-Roles` and is what makes these
headers safe: both are stripped first and set second, never appended to
whatever arrived. A request that was not authenticated carries neither, and a
request by a caller who is not a Customer carries roles but no customer id.

**A CORS preflight is answered without a token only on the browser-facing
surface.** A browser sends `OPTIONS` with `Origin` and
`Access-Control-Request-Method` before it has a token, and never sends one with
it, so refusing the handshake would read to a client as a broken CORS setup
rather than as a refusal. That is why `/api/**` and `/auth/**` — the routes a
browser application actually calls — are exempt.

Whether a route is browser-facing is a column of the same rule table that
decides what a request requires, and a required argument of each row rather
than a default. One table answers both questions, so a route cannot be added to
one and forgotten in the other, and `RoleAuthorizerTest` checks every row against
the policy so that an undeclared route fails the build instead of shipping.

It is scoped to those routes rather than to the HTTP method, and the
management endpoints are deliberately not on it. Keyed on the method, the
exemption would make "/actuator/** is the operator's" true of every method but
`OPTIONS`, so the answer to *may this reach the management endpoints* would
depend on the verb rather than on the route — and a rule with a method-shaped
exception is a rule that a later edit can quietly widen. A preflight to
`/actuator/**` therefore takes the ordinary authorization path and is refused for
want of a token. Nothing legitimate is put outside by that: no browser
application calls the management endpoints, which are read by monitoring and by
an operator directly. Both layers agree, which is what makes the invariant hold
end to end — the filter declines to exempt the route, and the shared CORS mapping
does not cover the actuator's handler mapping either, so a cross-origin
management preflight is never answered, whatever token it carries.

**Consequences:** One place to audit and rotate keys; downstream services
carry no security dependency. A caller can no longer reserve seats in
another Customer's name by setting a header, which is the whole of the
authorization decision for a request like `POST /api/v1/reservations` — a
service that trusted the inbound header would have no authorization at all.

**The authentication here is a configured demo credential set, not an identity
provider.** Worth stating plainly, because the rest of this ADR describes a real
trust boundary and it would be easy to read the whole document as a claim that
the login surface is production-grade password authentication. It is not:

- **The passwords are configuration, not stored credentials.** They come from
  the environment and are compared against a configured value per caller
  (`app.gateway.callers.*.password`). There is no password database, no hashing
  at rest and no identity provider, because there is nothing to authenticate
  against — the value in configuration *is* the credential.
- **Changing a password does not revoke anything.** It changes what the login
  surface will accept from then on. A token issued before the change remains
  valid until it expires, and a token issued before a caller was deleted stays
  valid too. There is no revocation list, no `jti` to deny, and no session to
  invalidate — the JWT is the whole of the session.
- **A token is valid until its expiry** and nothing shorter. `JWT_TTL` defaults
  to an hour and there is no refresh token, so the honest description of
  "logging out" is "stop sending it".
- **Nobody is rate-limited at the login surface.** `/auth/login` is the one
  endpoint reachable with no token, and it has no attempt limit, so it is
  guessable at whatever rate the network allows. Constant-time comparison and an
  identical response for an unknown username and a wrong password mean the
  endpoint does not leak *which* credentials exist; it does nothing about
  *how many* tries. Login brute-force protection is its own follow-up,
  [#43](https://github.com/skibkitty/ticketing-platform/issues/43), and is
  deliberately separate from the flash-sale rate limiting in
  [#13](https://github.com/skibkitty/ticketing-platform/issues/13), which is
  scoped to `POST /api/v1/reservations` and would not cover it.
- **Roles come from configuration too.** A caller's roles are whatever
  `app.gateway.callers.*.roles` says, so the role table is an operator's
  configuration decision rather than something a user can hold or change.

Production identity management — a credential store, hashing, rotation that
revokes, refresh and revocation, and a rate limit on the login endpoint — is a
follow-up, and it is the part of this boundary that has to change before the
platform carries real users. Nothing else in this ADR has to change with it: the
gateway is already the single place a token is verified, so replacing what
verifies it is a change behind one interface.

Making reservations Customer-only is a change in behaviour, and the one this
decision is most likely to be second-guessed on: an organizer or an admin can no
longer buy a seat with its own token. That is the correct answer given the
domain, and it is cheap to reverse *if the domain changes* — an organizer is
given a Customer identity, and the header follows from the role automatically,
because the emission is keyed on the role rather than on a table of logins. It
would be expensive to reverse the other way, which is why the authorization
table and the header filter ask `isCustomer()` rather than re-deriving the
answer from a role set each.

The safety of trusting these headers still rests on network hygiene, not on
protocol: nothing except our own services may reach an internal service. The
default `docker-compose.yml` therefore publishes no port for
reservation-service, payment-service or notification-service at all — they are
reachable by service name on the compose network, and by nothing else, with
`docker-compose.debug.yml` as the opt-in exception for host-side debugging. See
ADR 012, which records that decision, and ADR 013, which extends it to postgres,
kafka and kafka-ui, so the default file publishes one port in total: the
gateway's. A host port is a debugging convenience, never an access path: an
internal service with no gateway route in front of it is not safe to expose, and
one that reads a Customer's data by an id in the path has nothing of its own to
stop that. A host-local process can still forge the headers, which is accepted at
demo scale. If this ever runs on a shared or untrusted network, each service must
validate the JWT itself instead of trusting the headers, and the `X-Customer-Id`
derivation above stops being load-bearing for authorization only because each
service would be deriving it from the token itself.

**What holds the boundary, and where that is asserted.** The invariant is not
"the filters strip the headers" on its own — a strip that a later edit moved
inside a conditional would be a bypass that reads correctly. So the properties
are each a test, and a change to one of these has to break one of them:

- A client-supplied `X-User-Roles` or `X-Customer-Id` never reaches a service,
  on an authenticated request, on an unauthenticated one, and for a caller the
  gateway gives no such header at all — including an organizer forging a
  Customer id, which is stripped rather than left to pass (`UserRolesHeaderFilterTest`,
  `CustomerIdHeaderFilterTest`, and both halves end to end in
  `GatewayProxyBootTests`).
- The header is **overwritten, never appended**, so a value repeated by the
  client — one `X-User-Roles` sent twice by two `curl -H` flags, or
  `X-Customer-Id` as two values — cannot leave a second value behind for a
  downstream reading only the first.
- A request that was not authenticated propagates no identity headers at all.
- The value that does arrive is derived from the verified token, never from the
  username: `aTokenFromTheLoginRouteSpeaksForTheCustomersIdAndNotTheUsername`.
- A route for another service's data is not routed to this one, so "a service
  trusts `X-User-Roles`" never becomes "a service trusts `X-User-Roles` for a
  request that was never meant for it" (ADR 011).
- And the deployment, which is the other half: `platform-tests` fails the build
  if a published port reappears, on the default file or on an interface wider
  than loopback in the debugging override (ADR 012, ADR 013).

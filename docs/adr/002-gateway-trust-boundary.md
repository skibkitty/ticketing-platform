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

The same rule already applied to `X-User-Roles` and is what makes these
headers safe: both are stripped first and set second, never appended to
whatever arrived. A request that was not authenticated carries neither, and a
request by a caller who is not a Customer carries roles but no customer id.

**Consequences:** One place to audit and rotate keys; downstream services
carry no security dependency. A caller can no longer reserve seats in
another Customer's name by setting a header, which is the whole of the
authorization decision for a request like `POST /api/v1/reservations` — a
service that trusted the inbound header would have no authorization at all.

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
default `docker-compose.yml` binds the internal services' host ports
(8082-8084) to `127.0.0.1` rather than every interface, so host-side
debugging still works while the rest of the network cannot reach them at
all; a host-local process can still forge the headers, which is accepted at
demo scale. A host port is a debugging convenience, never an access path: an
internal service with no gateway route in front of it is not safe to expose,
and one that reads a Customer's data by an id in the query string has nothing
of its own to stop that. Remove the loopback bindings too once host-side
debugging is done. If this ever runs on a shared or untrusted network, each
service must validate the JWT itself instead of trusting the headers, and the
`X-Customer-Id` derivation above stops being load-bearing for authorization
only because each service would be deriving it from the token itself.

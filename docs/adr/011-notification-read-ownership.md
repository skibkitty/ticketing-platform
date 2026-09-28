# ADR 011: A Customer's notifications are read by identity, and the operator gets a separate route

**Status:** Accepted

**Context:** `GET /api/v1/notifications` took a `customerId` query parameter and
used it as the only scope on the read. The gateway's table had no rule for the
route at all, so it fell through to `/api/**` and was `AnyAuthenticated` — and
ADR 002's `X-Customer-Id`, which is the gateway's verified statement of who is
asking, was never consulted here. Anyone who could authenticate could read
anyone's inbox by changing a number, and `reservation-service` shows the
contrast: it already reads its `customerId` from the gateway-derived header
rather than from the request. The controller's own javadoc claimed "no gateway
route exists yet", which was wrong by the time it was written — the route
existed and was open.

**Decision:** The self-service read is scoped by the caller's identity and by
nothing else, and the operator's need to look at a Customer's inbox is served
by a second, separately authorized route.

## A Customer reads their own inbox by being themselves

`GET /api/v1/notifications` takes its `customerId` from `X-Customer-Id` and
makes the header required. There is no longer any way to name a Customer on
this route, so the question "may this caller read this Customer's row" has
exactly one answer: they are that Customer.

The `customerId` parameter is still accepted, and refused with 400 when it
disagrees with the header. It is not a capability — the value is already
proven by the gateway — so a client sending the id it holds keeps working
while one that has started believing it can choose whose inbox it reads fails
its tests. **400 rather than 403**: nothing about the caller's own permissions
is wrong, the request contradicts itself, and a 403 here would describe a rule
that does not exist.

Required-header has a second consequence worth stating: the route is
unreachable by anything that did not come through the gateway. A host-local
process debugging on the compose network can no longer read an inbox by
guessing an id, which is the same reason ADR 002 keeps the internal ports off
every interface.

## The operator may read any Customer's inbox, on a separate route

`GET /api/v1/admin/customers/{customerId}/notifications`, `ADMIN` only.

A ticketing platform has a real support need here — "they say they never got
the confirmation" — and refusing it entirely would mean the next such request
gets answered by opening a database console, which is strictly worse than an
audited route. So ADMIN may read another Customer's Notifications, and the
question is where that capability lives.

**A separate route, not a flag or a role check on the existing one.** The
alternatives considered:

- *One route, with the service checking `X-User-Roles` itself.* Rejected: it
  puts an authorization decision inside a service that holds no authentication
  of its own, which is exactly the boundary ADR 002 declines to cross. Every
  future service with a read would grow its own private copy of the rule.
- *A query flag such as `?all=true`.* Rejected: it makes the self-service
  route's meaning depend on a parameter, which is the property this ADR
  removes. It also makes a log line ambiguous about which read was used.
- *No operator access at all.* Rejected as too blunt: the need above is real,
  and the honest fallback is a database console rather than a smaller
  capability.

Keeping `/api/v1/notifications` meaning only "yours" means the self-service
read cannot be widened by accident — there is no flag to flip and no role to
add to the Customer rule — and a request log distinguishes an operator's read
from a Customer's without inspecting anything else. The `/api/v1/admin/**`
prefix is the operator's namespace for future routes, and `RoleAuthorizer` is
the only place ADMIN's reach is decided.

**The two rules are disjoint, and deliberately so.** `/api/v1/notifications/**`
is `CUSTOMER` and `/api/v1/admin/**` is `ADMIN`, so reaching the operator's
read means giving up the self-service one, and only a caller holding both roles
gets both. The self-service rule exists even though the service would refuse a
non-Customer anyway, for a reason worth being explicit about: a rule that reads
"any authenticated caller" is indistinguishable, at the gateway, from a rule
nobody has considered yet, and the Customer rule says what is true — the inbox
belongs to Customers, so it is Customers who have one.

**One routing caveat, stated rather than hidden.** The gateway's route for this
service claims the whole `/api/v1/admin/**` prefix, because a path pattern
cannot put a variable segment in the middle. The service still decides which
shapes exist, so an operator URL that is not served 404s there — but the prefix
is currently routed to notification-service, and a future admin route for
another service's data has to be added as a route *ahead* of this one rather
than under it. Worth remembering before the second admin route exists.

**Consequences:** Customer isolation is a property of the route rather than a
check somebody has to remember to write, and it holds for a Customer who also
holds ADMIN, since the self-service read never consults a role. Cross-Customer
reads are possible, deliberately, and visible as a different URL. What this
does *not* do is make the Notification rows non-sensitive: `/api/v1/admin/**`
reaches this service, and like every route it inherits ADR 002's requirement
that nothing but the gateway can reach the port. Widening operator access to
other services' data is a new decision per route, and the prefix is where it
will be argued.

The cursor needs no corresponding rule. `NotificationService.cursorAfter`
already issues a position rather than a grant, and a cursor is only ever read
within the `customerId` of the request that presents it, so a cursor taken
from one Customer's inbox cannot page another's — an empty page, not a leak.

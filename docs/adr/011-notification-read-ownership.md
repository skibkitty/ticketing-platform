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
every interface and ADR 012 keeps them unpublished altogether.

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
service names the operator path exactly —
`/api/v1/admin/customers/{id}/notifications` — and not the `/api/v1/admin/**`
prefix. It used to claim the whole prefix, on the stated grounds that a path
pattern cannot hold a variable in the middle. That was wrong: `PathPattern`, which
is what the gateway's `Path` predicate parses, holds a variable segment perfectly
well, and the reason is worth recording because the wrong belief was what made
the prefix seem necessary.

A prefix-wide route is a statement about routes nobody has written. The next
operator route will be for some other service's data — an admin view of an Event,
of a Refund — and under `/api/v1/admin/**` it would be proxied to
notification-service purely by virtue of sitting under the prefix, with whoever
added it having no reason to know. The service decides which shapes exist, so
today's answer would be a 404 from that service; the request would still have
crossed the gateway with the caller's identity headers on it, and a route added
later would silently belong to whichever service's route was listed first. Naming
the path means an admin route for another service's data is an independent route
with its own pattern, and the operator namespace is where the argument about it
happens rather than where the ordering of a YAML list does.

**The authorization rule stays wider than the route, deliberately.**
`RoleAuthorizer` decides `/api/v1/admin/**` is `ADMIN`, so a path under the prefix
that no service serves yet is refused to a Customer rather than falling through
to the `/api/**` catch-all as merely authenticated. Narrowing that rule to match
the narrowed route would be strictly worse: an unwritten operator path would
become reachable by any token rather than reserved. Failing closed on the route
and failing closed on the rule are separate decisions, and both are wanted —
which is why the two are named in different places and the difference is
commented in both.

**Consequences:** Customer isolation is a property of the route rather than a
check somebody has to remember to write, and it holds for a Customer who also
holds ADMIN, since the self-service read never consults a role. Cross-Customer
reads are possible, deliberately, and visible as a different URL. What this
does *not* do is make the Notification rows non-sensitive: the operator read
reaches this service, and like every route it inherits ADR 002's requirement
that nothing but the gateway can reach the port. Widening operator access to
other services' data is a new decision per route, and the prefix is where it
will be argued.

The cursor needs no corresponding rule. `NotificationService.cursorAfter`
already issues a position rather than a grant, and a cursor is only ever read
within the `customerId` of the request that presents it, so a cursor taken
from one Customer's inbox cannot page another's — an empty page, not a leak.

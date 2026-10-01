# ADR 014: A Customer's reservations are read by identity

**Status:** Accepted

**Context:** The write half of the reservation surface was already bound to the
caller. `POST /api/v1/reservations` requires the gateway-derived `X-Customer-Id`
(ADR 002) and books against it, and ADR 002's whole argument is that this header
is the gateway's verified statement of who is asking. The read half was not.

`GET /api/v1/reservations/{reservationId}` took the id from the path and no
Customer at all — `ReservationService.get(long reservationId)` had no customer
argument to scope by, so the lookup was `findById`. And
`GET /api/v1/reservations` took `?customerId=` and used it as the only scope.

The gateway did not close this gap, and could not have. `RoleAuthorizer` puts
`/api/v1/reservations/**` behind `CUSTOMER`, so a caller reaching either route
is already a Customer — but "is a Customer" is not "is *this* Customer", and the
authorization table has no view of whose data a read returns. So any valid
Customer could read any other Customer's reservation by changing one number, or
one path segment. ADR 011 records the same bug and the same fix for
notifications; reservations were the sibling it had not yet been applied to.

**Decision:** Both reads are scoped by the caller's identity and by nothing else,
and the scope is applied in the query rather than to its result.

## A Customer reads their own reservations by being themselves

`GET /api/v1/reservations/{reservationId}` takes the customer id from
`X-Customer-Id`, requires it, and passes it **into the lookup**:
`ReservationRepository.findByIdAndCustomerId(id, customerId)`.

Putting the Customer in the query rather than filtering a loaded result is the
part worth stating, because three things follow from it that a
load-then-compare would not give:

- **The row is never loaded when it is not yours.** There is no point in the code
  where a wrong Customer's Reservation exists in memory and has to be remembered
  not to be returned.
- **The read path's side effect cannot fire on it.** Reading an overdue
  `PENDING_PAYMENT` Reservation expires it, releases its seats and stages a
  `ReservationExpired` outbox event. Scoped in the query, a non-owner's request
  cannot trigger any of that on someone else's hold. This is a deliberate
  behaviour change: a refused read now has no effect at all.
- **Existence does not leak.** A non-owned id and an unissued id are both absent,
  so the same 404 answers both and a caller cannot probe which reservation ids
  are taken.

`ReservationService.get` takes the Customer as a required argument, so the
ownership rule is not something a second controller or a future call path can
leave out by forgetting a check in the web layer.

**404, not 403.** The row exists and the caller may not have it — but telling
them so would confirm the reservation is real, and the route has no operator
read to be a genuine exception to. The message is the same "was not found" an
unknown id gets. This follows ADR 011's reasoning that a 403 "would describe a
rule that does not exist here".

Required-header has the same second consequence ADR 011 records: the routes are
unreachable by anything that did not come through the gateway, so a host-local
process on the compose network cannot read a Reservation by guessing an id.

## The list is scoped the same way, and the parameter is refused rather than honoured

`GET /api/v1/reservations` takes its Customer from the header. The `customerId`
parameter is still accepted and **refused with 400** when it disagrees, on
exactly ADR 011's reasoning: redundant rather than dangerous, and refusing only
the mismatch means a client that has started believing it can choose whose
reservations it reads fails its own tests instead of receiving its own rows and
reporting "I have none" weeks later.

Removing the parameter outright was the alternative. It is the smaller API
surface, but ADR 011 already decided this question for the sibling route, and two
identity-scoped reads answering the same request differently is a worse contract
than one redundant parameter. Keeping them consistent means a client that has
learned how the notifications route behaves already knows how this one behaves.

## The gateway's trust boundary is unchanged

Nothing here weakens ADR 002. The gateway still removes any inbound
`X-Customer-Id` unconditionally, derives the replacement from the verified JWT
subject, and publishes it only for a caller that `isCustomer()`. This ADR adds no
second identity source: there is no path by which a caller-supplied customer id
becomes authoritative, and the refusal of a mismatched parameter happens at the
service, where the header it is compared against is one the gateway already
vouched for.

## Two seams, one invariant

The claim "Customer A cannot read Customer B's reservations" spans two processes,
so it is asserted at both seams rather than in one place that could pass while
half of it was wrong:

- **`GatewayProxyBootTests`** — the identity half. A JWT for Customer A produces
  `X-Customer-Id: A` on a reservation read; a forged inbound header becomes A
  however it is sent (once, twice, comma-joined); a `?customerId=B` cannot
  displace the header; ORGANIZER and ADMIN are refused on the route.
- **`ReservationFlowBootTests`** — the enforcement half, against a real
  controller, service and database. A reads A's reservation by id; A is 404 on
  B's and the error body carries none of B's fields; A's list is only A's;
  `?customerId=B` is 400; no header is 400; a refused read leaves the owner's row,
  its seats and its outbox untouched; creation still works.

Neither half is sufficient alone. A gateway that published no identity would leave
the service nothing to scope by; a service that trusted a parameter would not care
what the gateway published.

## Consequences

**A read that omits the header stops working**, which is the intended effect and
not a free change: any caller not coming through the gateway — a script against
the debug port, a future internal service — must now supply a customer id. This
is the same consequence ADR 011 accepted for notifications.

**Reading an overdue reservation is still what expires it, but only for its
owner.** The scheduled `HoldExpirer` is unaffected, so a hold is still released
on time; only the incidental trigger available to a stranger is gone.

**`GET /api/v1/payments/{reservationId}` is the same shape and is still open** —
[#22](https://github.com/skibkitty/ticketing-platform/issues/22). It is out of
scope here and deliberately not fixed: it is a different service, and folding it
in would make this ADR a change to two contracts at once. The fix is ADR 011's
and this one's, applied a third time.
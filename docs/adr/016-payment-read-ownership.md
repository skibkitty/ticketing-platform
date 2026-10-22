# ADR 016: A Customer's payment is read by identity

**Status:** Accepted

**Context:** The spec's contract for the payment-status read is
`GET /api/v1/payments/{reservationId}`, "returning the Payment; 404 when absent".
Built as written, that is a lookup by reservation id and nothing else, and it
would reopen the hole ADR 011 closed for notifications and ADR 014 closed for
reservations: any authenticated caller reads any Customer's payment, including
the amount, by changing one path segment. ADR 014 named this route as the
remaining instance and left it to
[#22](https://github.com/skibkitty/ticketing-platform/issues/22).

Payment-service could not have scoped the read, because it did not know who a
Payment belonged to. `payments` held `reservation_id`, `amount_cents` and the
outcome, but no Customer. The owner was already on the wire, though:
`ReservationCreated` carries `customerId`, and the consumer validates it as a
positive id before it creates the Payment. It just never stored it.

The gateway did not close the gap either. `/api/v1/payments/**` had no rule of
its own and fell through to the `/api/**` catch-all as `AnyAuthenticated`.

**Decision:** The payment read is scoped by the caller's identity, in the query,
in the same way as ADR 014. It is the same fix a third time, not a new design.

## Payment-service records the owner it is already told about

`payments.customer_id` (V4) is written from the `ReservationCreated` payload in
the same `INSERT ... ON CONFLICT (reservation_id) DO NOTHING` that creates the
row. A duplicate event never rewrites it: the first event to claim the
reservation sets the owner.

The alternatives were worse:

- *Ask reservation-service who owns the reservation, per read.* That is a
  synchronous dependency on the route that has a circuit breaker precisely
  because payment-service should degrade on its own, and it would make a payment
  lookup fail whenever reservation-service does.
- *Have the gateway check ownership.* The gateway has no view of whose data a
  read returns, which is what ADR 014 records for the same proposal.

## The read

`GET /api/v1/payments/{reservationId}` requires `X-Customer-Id` and passes it
into `findByReservationIdAndCustomerId`. As in ADR 014, this means:

- a Payment that is not the caller's is never loaded;
- someone else's reservation id and an unissued one get the same 404 with the
  same wording, so the response does not confirm that a reservation was paid for;
- with no header the request is refused with 400 before any lookup, so the route
  cannot be reached except through the gateway.

**404, not 403**, for ADR 014's reason: a 403 would confirm the payment exists.

There is no `?customerId=` parameter to refuse. This route never had one, unlike
the two reads ADR 011 and ADR 014 changed, so there is no existing client to
keep compatible.

## Rows from before V4 are readable by nobody

`customer_id` is nullable and not backfilled. The owner of an older row is only
known in the `reservation` schema, and ADR 005 rules out cross-schema queries. A
null owner matches no caller, so those rows fail closed with a 404. That is the
right failure for a demo platform whose data is recreated with the stack, and
the column can be tightened to `NOT NULL` once no such rows remain.

## The gateway says whose route it is

`/api/v1/payments/**` is `CUSTOMER`-only in `RoleAuthorizer`, for the reason ADR
011 gives for notifications. The service would refuse an organizer anyway, since
the gateway publishes no `X-Customer-Id` for one. But a catch-all rule would say
that a payment belongs to anyone holding a token. The circuit breaker is
unaffected, because refused requests never reach it.

**Consequences:** All three Customer-owned reads (reservations, notifications and
payments) are now scoped by identity, and none can be widened by a client. There
is no operator route for payments. If support needs one, it is a separate
`/api/v1/admin/**` route and a new decision, following ADR 011.

# Ticketing Platform

An event ticketing & reservation system: customers place time-limited holds
on seats for an event, pay, and either get a confirmed seat or — on payment
failure — the seats are automatically released for someone else.

## Language

**Event**:
An occasion (name, venue, date) that customers buy into. The owner of the
seats that form its inventory.
_Avoid_: show, concert

**Seat**:
A specific physical location in an Event (section, row, number); the atomic
inventory unit. Lifecycle: `AVAILABLE` → `HELD` → `SOLD`. A sold Seat is the
customer's ticket — there is no separate Ticket entity within this scope.
_Avoid_: ticket, place

**Hold**:
The exclusive, time-limited claim a Reservation places on a set of Seats.
A Seat under a Hold is unavailable to everyone else until the Hold is
converted (paid), released by the sweep, or cancelled.

**Reservation**:
A Customer's intent to buy a specific set of Seats for an Event; the
customer-facing artifact. States: `PENDING_PAYMENT`, `CONFIRMED`,
`CANCELLED`, `EXPIRED`.
_Avoid_: order, booking

**Cancelled vs Expired**:
Both release the Seats; they differ in what happened to the payment.
`CANCELLED` means the payment was declined. `EXPIRED` means the Hold lapsed
with no payment outcome at all (released by the sweep).

**Payment**:
The money movement for a Reservation — one per Reservation. States:
`PENDING`, `SUCCEEDED`, `FAILED`.

**Customer**:
The person a Reservation belongs to; referenced by identifier only, no
profile data is stored.
_Avoid_: user, account
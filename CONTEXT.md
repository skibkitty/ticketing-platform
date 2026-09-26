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

**Notification**:
The message a Customer is told about a Reservation reaching a terminal
state. One per Reservation per terminal state, so a Reservation that is
cancelled and later expires would owe two. Types: `RESERVATION_CONFIRMED`,
`RESERVATION_CANCELLED`, `RESERVATION_EXPIRED` — mirroring the
Reservation's own states, minus the non-terminal `PENDING_PAYMENT`.
A Notification's `message` is the text it was sent with, not the
Notification's name for itself.
_Avoid_: email, receipt, ticket

**Terminal reservation event**:
The `reservation.events.v1` event that ends a Reservation's life —
`ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired` — and
the only trigger for a Notification. Sending is simulated by a log line;
no channel is wired up yet.

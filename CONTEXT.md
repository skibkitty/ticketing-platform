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
before a payment outcome was applied (released by the sweep). An outcome
that lands afterwards changes nothing about the Reservation; a late success
is refunded (see **Refund**).

**Payment**:
The money movement for a Reservation — one per Reservation. States:
`PENDING`, `SUCCEEDED`, `FAILED`, `REFUNDED` (see **Refund**).

**Refund**:
The compensating step that returns a late-settled Payment's money: a
Payment that succeeded after its Reservation had already `EXPIRED` moves
from `SUCCEEDED` to the terminal `REFUNDED`. A state of the one Payment,
not a record of its own. Triggered by reservation-service's
`LatePaymentRejected`, never by a late `PaymentFailed` (nothing was
charged).

**Customer**:
The person a Reservation belongs to; referenced by identifier only, no
profile data is stored. A caller is a Customer when it holds the `CUSTOMER`
role, and only then is its id a `Customer.id` — holding `ORGANIZER` or `ADMIN`
is a capability, not a second kind of Customer, so an organizer's request
carries no Customer identity.
_Avoid_: user, account

**Caller**:
Whatever authenticated to the platform and holds roles — a Customer, an
Event's organizer, or the platform operator. A caller has an id of its own,
which is the token subject; for a Customer that id is its `Customer.id`, and
for anything else it is only ever its own. An id belongs to one caller and no
other — two callers sharing one would be one identity wearing two logins.

**Notification**:
The message a Customer is told about a Reservation reaching a terminal
state, or about a Refund of its Payment. One per Reservation per type, so
a Reservation that is cancelled and later expires would owe two. Types:
`RESERVATION_CONFIRMED`, `RESERVATION_CANCELLED`, `RESERVATION_EXPIRED` —
mirroring the Reservation's own states, minus the non-terminal
`PENDING_PAYMENT` — and `PAYMENT_REFUNDED`, which follows a
`RESERVATION_EXPIRED` to explain the charge.
A Notification's `message` is the text it was sent with, not the
Notification's name for itself.
_Avoid_: email, receipt, ticket

**Terminal reservation event**:
The `reservation.events.v1` event that ends a Reservation's life —
`ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired` — and
one of the two triggers for a Notification; the other is
`payment.events.v1`'s `PaymentRefunded`. Sending is simulated by a log line;
no channel is wired up yet.

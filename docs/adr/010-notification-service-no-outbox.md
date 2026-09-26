# ADR 010: notification-service has no outbox

**Status:** Accepted

**Context:** ADR 003 makes the transactional outbox the way every service
publishes the events *it* owns, so that a business change and its event
commit together. notification-service is the end of the saga: it consumes
`reservation.ReservationConfirmed` / `ReservationCancelled` /
`ReservationExpired` and writes a `Notification` row plus a simulated send.
It has no downstream saga participant to tell, so it owns no events and has
no outbox table, publisher, or `app.outbox` configuration.

**Decision:** notification-service does not publish, and therefore has no
outbox. The dead-letter copy a poison record is quarantined as (ADR 008) is
not an outbox concern: it is written by the recoverer on a record the
business transaction already rolled back, so there is nothing to keep
atomic with.

Its idempotency needs are the same shape as every other consumer (ADR 004),
and it uses both claims:

- `processed_events` on `eventId` — the ADR 004 claim, in the same
  transaction as the `Notification` row, so a redelivery is a no-op and a
  failed write rolls the claim back for a retry.
- `notifications` on `(reservation_id, type)` — a second, deliberate
  deviation. Dedupe on `eventId` stops only the *same* event arriving
  twice; a differently ID'd repeat of a transition the Customer was already
  told about would get past it, and here that means telling them twice about
  one Reservation. Claimed the same way (`ON CONFLICT DO NOTHING`), so a
  repeat stays a successful no-op rather than an integrity exception that
  would be retried and dead-lettered.

The Notification type vocabulary is `RESERVATION_CONFIRMED`,
`RESERVATION_CANCELLED`, `RESERVATION_EXPIRED` — one per terminal
Reservation state. `reservation.ReservationCreated` also arrives on the
shared topic and is deliberately ignored, not dead-lettered: it is another
service's business, and a terminal event that does not match a known type
is the one that is quarantined.

**Consequences:** notification-service is a pure consumer, so it can never
be the reason a saga step is half-done: there is no event of its own to
lose. The cost is that the simulated send is at-least-once, not exactly
once — the row and its log line commit together, but a crash between the
commit and the offset commit replays the event, and the `processed_events`
claim turns the replay into a no-op rather than a second send. Anything
downstream that must *act* on a Notification will need a real channel, and
at that point the send needs its own delivery record to be retryable
honestly.

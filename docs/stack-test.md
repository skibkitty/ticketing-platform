# Late-settlement refund stack test

The refund for a payment that settles after its Hold expired
([ADR 017](adr/017-refund-late-settled-payment.md)) spans three services and
two topics. Each service's Testcontainers suite tests its own hop, using
payload literals the suites duplicate. This test is the one that runs the whole
path on the real stack, with real topics and the services' own serialization.

`platform-tests/src/test/java/com/raydans/platform/LateSettlementRefundStackTest.java`,
against a compose stack that is already up:

1. Checks that the four topics (`reservation.events.v1`, `payment.events.v1`
   and their `.dlt`s) exist, and records the dead-letter topics' end offsets.
2. Creates an Event with four Seats, two under the decline threshold and two
   over it.
3. **Controls:** books one cheap Seat (it ends `CONFIRMED`, Payment `SUCCEEDED`)
   and one expensive Seat (it ends `CANCELLED`, Payment `FAILED`).
4. **Late settlements:** stops payment-service, books the other cheap Seat and
   the other expensive Seat, waits for both Holds to lapse (`EXPIRED`), then
   starts payment-service again. It settles both late: one success, one failure.
5. Waits for the stack to go quiet: no unpublished outbox rows, and every
   consumer group at zero lag, twice in a row.
6. Checks the end state through the API and in Postgres:
   - All four Reservations are in their expected state. Every Seat is
     `AVAILABLE` except the confirmed one, which is `SOLD`.
   - Only the late success is `REFUNDED`, with `refundedAt` set. The others
     have `refundedAt: null`.
   - The late success's inbox is `PAYMENT_REFUNDED`, then `RESERVATION_EXPIRED`
     (newest first). The other three Reservations each have their one notice.
   - Exactly one `LatePaymentRejected`, one `PaymentRefunded` and one
     `PAYMENT_REFUNDED` row, all for the late success. One Payment per
     Reservation.
   - Nothing new on either dead-letter topic.
7. **Replay:** stops the three services, resets all three consumer groups to
   offset zero, starts them again, and repeats step 5 and every check in step 6.
   Every event the stack has ever carried is delivered a second time.

## Running it

It needs the stack and nothing else. Don't run it alongside `mvn verify` on a
small machine (see the README).

```bash
HOLD_DURATION=PT30S docker compose up --build -d
mvn -pl platform-tests -am -Pstack-test test -Dsurefire.failIfNoSpecifiedTests=false
docker compose down
```

The test fails fast if the stack's Hold is longer than two minutes. It leaves
the stack running, with payment-service restarted.

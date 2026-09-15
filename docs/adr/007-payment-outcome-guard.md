# ADR 007: Ignore payment outcomes for reservations no longer pending payment

**Status:** Accepted

**Context:** payment-service's outcome is asynchronous, so a `PaymentSucceeded`/`PaymentFailed` created just before the hold expires can arrive *after* the sweep has already marked the reservation `EXPIRED` and released its seats. Applying that late outcome to a no-longer-pending reservation would re-flip seats the reservation no longer owns; the `@Version` conflict would roll back the whole transaction including the `processed_events` insert, turning the message into a poison event redelivered forever.

**Decision:** The outcome consumer in reservation-service only applies a payment outcome while the reservation is `PENDING_PAYMENT`. Otherwise it records the event as processed and does nothing.

**Consequences:** A settled-but-late outcome is dropped rather than reconciled — the 10-minute hold is binding and the seats may already belong to someone else. No reconcile loop, no poison messages, and a `PaymentFailed` arriving after expiry leaves the reservation `EXPIRED`, not `CANCELLED`.
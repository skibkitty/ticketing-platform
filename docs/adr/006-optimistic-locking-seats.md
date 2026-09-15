# ADR 006: Optimistic locking on Seat instead of pessimistic row locks

**Status:** Accepted

**Context:** Multiple customers can attempt to hold the same seat(s)
concurrently, especially in the first seconds of a popular event's on-sale.

**Decision:** `Seat.version` (JPA `@Version`). Each hold attempt updates
seats with an implicit `WHERE version = ?`; a concurrent winner causes the
loser's update to affect zero rows, and the whole reservation transaction
rolls back with a 409.

**Consequences:** No lock held across the transaction, so no
multi-seat lock-ordering deadlock risk. Under very high contention on one
specific seat, this produces more retries/conflicts than a pessimistic lock
would — an acceptable tradeoff since seat-level contention is a short-lived
spike at sale-open, not sustained load.

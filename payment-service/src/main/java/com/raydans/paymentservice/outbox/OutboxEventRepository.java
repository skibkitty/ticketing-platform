package com.raydans.paymentservice.outbox;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * Candidate batch, no locks held: {@code SKIP LOCKED} only filters out rows whose lock
     * is currently held by another publisher's in-flight transaction. The actual per-row
     * claim happens in the publisher's own transaction (see {@link OutboxPublisher}).
     */
    @Query(
            value =
                    "SELECT * FROM payment.outbox_events "
                            + "WHERE published_at IS NULL ORDER BY id ASC LIMIT 20 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedBatch();

    /**
     * Re-claims a single row inside the caller's transaction with a row lock, skipping it if
     * it was already claimed-and-published by a concurrent publisher. The lock makes the row
     * ineligible for {@link #findUnpublishedBatch()} (SKIP LOCKED) until this transaction
     * commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEventEntity e where e.id = :id and e.publishedAt is null")
    Optional<OutboxEventEntity> findAndLockPending(@Param("id") Long id);
}

package com.raydans.reservationservice.reservation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Atomically claims an inbound event for idempotency (ADR 004). Runs in whatever
     * transaction the caller provides: the claim, the reservation state change, and the
     * outbox row therefore commit or roll back together. Returns 1 when this transaction
     * took the claim, or 0 when the event was already claimed (by a previous or a concurrent
     * transaction), in which case the caller treats the delivery as a no-op.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO reservation.processed_events (event_id) VALUES (:eventId) "
                            + "ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int tryClaim(@Param("eventId") UUID eventId);
}
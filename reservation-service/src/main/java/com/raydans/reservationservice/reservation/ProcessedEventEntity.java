package com.raydans.reservationservice.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only idempotency log (ADR 004): one row per claimed inbound event id.
 *
 * <p>Retention requirement: rows must survive at least as long as every source
 * topic's Kafka retention <em>plus</em> any replay/DLT re-drive window, otherwise a
 * re-delivered event would be wrongly seen as "already processed". This is a
 * known gap (ADR 009) — a purge job must never delete before that horizon.
 */
@Entity
@Table(schema = "reservation", name = "processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false, insertable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    public ProcessedEventEntity(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
package com.raydans.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The Kafka wire format for every event on every topic. Each message is keyed
 * by {@link #aggregateId} so ordering per aggregate is preserved, and
 * {@link #correlationId} traces one request end-to-end through every service.
 *
 * @param eventId     unique id of this event instance (deduplication)
 * @param eventType   the domain event name, e.g. {@code reservation.SeatHeld}
 * @param occurredAt  when the event happened
 * @param correlationId request id carried across the saga
 * @param aggregateId the entity this event is about (ordering key)
 * @param payload     the typed body of the event
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        UUID aggregateId,
        T payload) {}
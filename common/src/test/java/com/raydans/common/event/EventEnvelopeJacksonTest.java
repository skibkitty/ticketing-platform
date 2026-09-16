package com.raydans.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeJacksonTest {

    record SeatHeldPayload(String section, String row, int seatNumber, int priceCents) {}

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsThroughJsonWithTypedPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-16T15:00:00Z");
        EventEnvelope<SeatHeldPayload> envelope = new EventEnvelope<>(
                eventId, "reservation.SeatHeld", occurredAt, "corr-1", aggregateId,
                new SeatHeldPayload("A", "1", 12, 4500));

        String json = mapper.writeValueAsString(envelope);

        EventEnvelope<SeatHeldPayload> decoded = mapper.readValue(
                json,
                TypeFactory.defaultInstance().constructParametricType(EventEnvelope.class, SeatHeldPayload.class));

        assertThat(decoded).isEqualTo(envelope);
        assertThat(decoded.payload()).isEqualTo(new SeatHeldPayload("A", "1", 12, 4500));
    }
}
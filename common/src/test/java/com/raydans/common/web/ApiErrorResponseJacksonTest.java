package com.raydans.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiErrorResponseJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesToTheSpecErrorContract() {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.parse("2026-09-16T15:00:00Z"),
                409,
                "Conflict",
                "Seat A-1-12 is already taken",
                "/api/v1/reservations",
                List.of("seatIds[0]: seat 12 is unavailable"));

        JsonNode json = mapper.valueToTree(error);

        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("timestamp", "status", "error", "message", "path", "details");
        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(json.get("error").asText()).isEqualTo("Conflict");
        assertThat(json.get("details").isArray()).isTrue();
        assertThat(json.get("details").get(0).asText()).isEqualTo("seatIds[0]: seat 12 is unavailable");
    }

    @Test
    void roundTripsFromJson() throws Exception {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.parse("2026-09-16T15:00:00Z"),
                400,
                "Bad Request",
                "Invalid payload",
                "/api/v1/reservations",
                List.of("seatIds must not be empty", "eventId must not be null"));

        String json = mapper.writeValueAsString(error);

        assertThat(mapper.readValue(json, ApiErrorResponse.class)).isEqualTo(error);
    }
}
package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventSeatBootTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsEventWithSeatsAndListsThemBack() {
        Map<String, Object> body = Map.of(
                "name", "Opening Night",
                "venue", "Metropolitan Opera",
                "eventDate", "2026-11-01T19:30:00Z",
                "seats", List.of(
                        Map.of("section", "Orchestra", "row", "A", "seatNumber", 1),
                        Map.of("section", "Orchestra", "row", "A", "seatNumber", 2)));

        ResponseEntity<Map> created = rest.postForEntity("/api/v1/events", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("Location")).isNotBlank();

        @SuppressWarnings("unchecked")
        List<Number> seatIds = (List<Number>) created.getBody().get("seatIds");
        assertThat(seatIds).hasSize(2);
        Number eventId = (Number) created.getBody().get("eventId");

        ResponseEntity<List> available =
                rest.getForEntity("/api/v1/events/" + eventId + "/seats?status=AVAILABLE", List.class);
        assertThat(available.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(available.getBody()).hasSize(2);
    }

    @Test
    void rejectsDuplicateSeatWithinOneEventAsConflict() {
        Map<String, Object> body = Map.of(
                "name", "Opening Night",
                "venue", "Metropolitan Opera",
                "eventDate", "2026-11-01T19:30:00Z",
                "seats", List.of(
                        Map.of("section", "Orchestra", "row", "A", "seatNumber", 1),
                        Map.of("section", "Orchestra", "row", "A", "seatNumber", 1)));

        ResponseEntity<Map> response = rest.postForEntity("/api/v1/events", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("status", 409);
    }

    @Test
    void listsSeatsForUnknownEventAsNotFound() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/events/422/seats", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }
}
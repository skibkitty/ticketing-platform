package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsEventWithSeatsAndListsThemBack() {
        ResponseEntity<Map> created = postEvent("Opening Night", List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1),
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 2)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("Location")).isNotBlank();
        assertThat(created.getBody().get("eventId")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Number> seatIds = (List<Number>) created.getBody().get("seatIds");
        assertThat(seatIds).hasSize(2);

        Number eventId = (Number) created.getBody().get("eventId");
        ResponseEntity<List> all = rest.getForEntity("/api/v1/events/" + eventId + "/seats", List.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).hasSize(2);
    }

    @Test
    void seatsDefaultToAvailableAndFilterWorks() {
        ResponseEntity<Map> created = postEvent("Opening Night", List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1)));
        Number eventId = (Number) created.getBody().get("eventId");

        ResponseEntity<List> available =
                rest.getForEntity("/api/v1/events/" + eventId + "/seats?status=AVAILABLE", List.class);
        assertThat(available.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(available.getBody()).hasSize(1);

        ResponseEntity<List> sold = rest.getForEntity("/api/v1/events/" + eventId + "/seats?status=HELD", List.class);
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sold.getBody()).isEmpty();
    }

    @Test
    void rejectsDuplicateSeatWithinOneEventAsConflict() {
        ResponseEntity<Map> created = postEvent("Opening Night", List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1),
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(created.getBody()).containsEntry("status", 409);
    }

    @Test
    void databaseUniquenessConstraintRejectsSameEventDuplicateRows() {
        ResponseEntity<Map> created = postEvent("Opening Night", List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1)));
        Number eventId = (Number) created.getBody().get("eventId");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservation.seats (event_id, section, \"row\", seat_number) VALUES (?, 'Orchestra', 'A', 1)",
                eventId.longValue()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_seats_event_section_row_number");
    }

    @Test
    void seatUniquenessViolationRollsBackEventAndSeatsTogether() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO reservation.events (name, venue, event_date) VALUES ('Rollback Test', 'Metropolitan Opera', now())");
            Long eventId = jdbc.queryForObject(
                    "SELECT id FROM reservation.events WHERE name = 'Rollback Test'", Long.class);
            jdbc.update(
                    "INSERT INTO reservation.seats (event_id, section, \"row\", seat_number) VALUES (?, 'Orchestra', 'A', 1)",
                    eventId);
            jdbc.update(
                    "INSERT INTO reservation.seats (event_id, section, \"row\", seat_number) VALUES (?, 'Orchestra', 'A', 1)",
                    eventId);
        })).isInstanceOf(DataIntegrityViolationException.class);

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.events WHERE name = 'Rollback Test'", Integer.class);
        assertThat(events).isZero();
    }

    @Test
    void listsSeatsForUnknownEventAsNotFound() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/events/422/seats", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    @Test
    void rejectsUnknownSeatStatusAsBadRequest() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/events/1/seats?status=BOGUS", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
    }

    @Test
    void rejectsBlankFieldsAsBadRequest() {
        ResponseEntity<Map> response = postEvent("", List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
    }

    private ResponseEntity<Map> postEvent(String name, List<Map<String, Object>> seats) {
        Map<String, Object> body = Map.of(
                "name", name,
                "venue", "Metropolitan Opera",
                "eventDate", "2026-11-01T19:30:00Z",
                "seats", seats);
        return rest.postForEntity("/api/v1/events", body, Map.class);
    }
}
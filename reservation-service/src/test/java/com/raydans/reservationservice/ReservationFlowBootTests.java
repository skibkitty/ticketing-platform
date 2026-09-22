package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.raydans.reservationservice.outbox.OutboxPublisher;
import com.raydans.reservationservice.reservation.HoldExpirer;
import com.raydans.reservationservice.web.ReservationController;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationFlowBootTests {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String CUSTOMER_HEADER = ReservationController.CUSTOMER_HEADER;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxPublisher outboxPublisher;

    @Autowired
    HoldExpirer holdExpirer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.outbox.poll-interval-ms", () -> "60000");
        registry.add("app.hold.expire-interval-ms", () -> "60000");
    }

    @Test
    void reservationCreatedReachesKafkaHoldsSeatsAndIsReadable() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000),
                Map.of("section", "Orchestra", "row", "B", "seatNumber", 2, "priceCents", 12000)));

        ResponseEntity<Map> reservation = postReservation(created.eventId(),
                List.of(created.seatIds().get(0), created.seatIds().get(1)), 99L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number reservationId = (Number) reservation.getBody().get("id");
        assertThat(reservation.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(reservation.getBody()).containsEntry("amountCents", 27000);
        String correlationId = reservation.getHeaders().getFirst(CORRELATION_HEADER);
        assertThat(correlationId).isNotBlank();

        outboxPublisher.poll();

        ConsumerRecord<String, String> record = awaitMessage(reservationId.longValue());
        assertThat(record.key()).isEqualTo(new UUID(0L, reservationId.longValue()).toString());
        assertThat(record.headers().lastHeader(CORRELATION_HEADER)).isNotNull();
        assertThat(new String(record.headers().lastHeader(CORRELATION_HEADER).value()))
                .isEqualTo(correlationId);

        assertThat(record.value())
                .contains("\"eventType\":\"reservation.ReservationCreated\"")
                .contains("\"correlationId\":\"" + correlationId + "\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":99")
                .contains("\"amountCents\":27000");

        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.seats WHERE status = 'HELD'", Integer.class);
        assertThat(held).isEqualTo(2);
        awaitOutboxPublished(1);

        ResponseEntity<Map> byId = rest.getForEntity("/api/v1/reservations/" + reservationId, Map.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(byId.getBody()).containsEntry("amountCents", 27000);

        ResponseEntity<List> byCustomer =
                rest.getForEntity("/api/v1/reservations?customerId=99", List.class);
        assertThat(byCustomer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byCustomer.getBody()).hasSize(1);
    }

    @Test
    void secondReservationForSameSeatReturns409AndFirstHoldIsUntouched() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "A", "seatNumber", 1, "priceCents", 5000)));

        ResponseEntity<Map> first =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 7L);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 8L);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("status", 409);

        Integer heldForEvent = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.seats WHERE event_id = ? AND status = 'HELD'",
                Integer.class, created.eventId());
        assertThat(heldForEvent).isEqualTo(1);
        Integer reservationsForLoser = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.reservations WHERE customer_id = 8", Integer.class);
        assertThat(reservationsForLoser).isZero();
        long firstReservationId = ((Number) first.getBody().get("id")).longValue();
        Integer seatLinks = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.reservation_seats WHERE reservation_id = ?",
                Integer.class, firstReservationId);
        assertThat(seatLinks).isEqualTo(1);
    }

    @Test
    void getReservationForUnknownIdReturns404() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/reservations/422", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    @Test
    void expiredHoldReleasesSeatAndExpiresReservationForReReservation() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "A", "seatNumber", 1, "priceCents", 4000)));

        ResponseEntity<Map> first =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 21L);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) first.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", seatId);
        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);

        holdExpirer.expire();

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("AVAILABLE");
        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId);
        assertThat(reservationStatus).isEqualTo("EXPIRED");

        ResponseEntity<Map> again =
                postReservation(created.eventId(), List.of(seatId), 22L);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void expiringReservationDoesNotReleaseSeatWhoseHoldIsStillLive() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Terrace", "row", "A", "seatNumber", 1, "priceCents", 3000)));

        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 31L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        String heldUntil = jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", String.class, seatId);

        holdExpirer.expire();

        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId);
        assertThat(reservationStatus).isEqualTo("EXPIRED");

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("HELD");
        String stillHeldUntil = jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(stillHeldUntil).isEqualTo(heldUntil);

        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", seatId);
        holdExpirer.expire();
        seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("AVAILABLE");
    }

    private CreatedEvent postEvent(List<Map<String, Object>> seats) {
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/events", Map.of(
                "name", "Opening Night",
                "venue", "Metropolitan Opera",
                "eventDate", "2026-11-01T19:30:00Z",
                "seats", seats), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        List<Number> seatIds = (List<Number>) response.getBody().get("seatIds");
        return new CreatedEvent(((Number) response.getBody().get("eventId")).longValue(),
                seatIds.stream().map(Number::longValue).toList());
    }

    private ResponseEntity<Map> postReservation(long eventId, List<Long> seatIds, long customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("eventId", eventId, "seatIds", seatIds), headers);
        return rest.postForEntity("/api/v1/reservations", entity, Map.class);
    }

    private void awaitOutboxPublished(int expectedRows) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer published = jdbc.queryForObject(
                    "SELECT count(*) FROM reservation.outbox_events WHERE published_at IS NOT NULL", Integer.class);
            if (published != null && published >= expectedRows) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting outbox publish", ex);
            }
        }
        throw new AssertionError("Outbox event was not marked published within 10s");
    }

    private ConsumerRecord<String, String> awaitMessage(long reservationId) {
        String expectedKey = new UUID(0L, reservationId).toString();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(OutboxPublisher.RESERVATION_EVENTS_TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(OutboxPublisher.RESERVATION_EVENTS_TOPIC)) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No ReservationCreated message arrived for reservation " + reservationId);
    }

    private record CreatedEvent(long eventId, List<Long> seatIds) {}
}
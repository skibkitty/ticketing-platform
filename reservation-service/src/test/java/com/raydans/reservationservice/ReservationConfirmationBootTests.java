package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.outbox.OutboxPublisher;
import com.raydans.reservationservice.web.ReservationController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T06 end-to-end: a Reservation that reaches PENDING_PAYMENT, receives a {@code PaymentSucceeded},
 * and is driven to CONFIRMED with its seats SOLD plus a {@code ReservationConfirmed} published —
 * with the ADR 007 guard and ADR 004 idempotency proven alongside.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConfirmationBootTests {

    static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";
    static final String CUSTOMER_HEADER = ReservationController.CUSTOMER_HEADER;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @BeforeAll
    static void createTopics() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000))) {
            admin.createTopics(List.of(new NewTopic(PAYMENT_EVENTS_TOPIC, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to pre-create topic " + PAYMENT_EVENTS_TOPIC, ex);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    OutboxPublisher outboxPublisher;

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
    void paymentSucceededConfirmsReservationSellsSeatsAndPublishesReservationConfirmed()
            throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000),
                Map.of("section", "Orchestra", "row", "B", "seatNumber", 2, "priceCents", 12000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 99L);
        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");

        UUID outcomeEventId = UUID.randomUUID();
        String correlationId = "corr-confirm-" + reservationId;
        producePaymentSucceeded(outcomeEventId, reservationId, correlationId);

        awaitReservationStatus(reservationId, "CONFIRMED");
        for (long seatId : created.seatIds()) {
            assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        }
        awaitProcessed(outcomeEventId);

        outboxPublisher.poll();

        ConsumerRecord<String, String> confirmed = awaitOnTopic(OutboxPublisher.RESERVATION_EVENTS_TOPIC,
                record -> reservationId == parseReservationId(record.value()));
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.key()).isEqualTo(new UUID(0L, reservationId).toString());
        assertThat(confirmed.value())
                .contains("\"eventType\":\"reservation.ReservationConfirmed\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":99")
                .contains("\"eventId\":" + created.eventId())
                .contains("\"amountCents\":27000");
        assertThat(new String(confirmed.headers().lastHeader(CorrelationIdFilter.HEADER_NAME).value()))
                .isEqualTo(correlationId);

        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationConfirmed'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    void outcomeForReservationNoLongerPendingIsANoOpMarkedProcessed() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "A", "seatNumber", 1, "priceCents", 5000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 7L);
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET status = 'EXPIRED' WHERE id = ?", reservationId);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentSucceeded(outcomeEventId, reservationId, "corr-guard-7");

        awaitProcessed(outcomeEventId);
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationConfirmed'",
                Integer.class, reservationId);
        assertThat(outboxRows).isZero();
    }

    @Test
    void duplicateOutcomeDeliveryNorDoubleConfirms() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "A", "seatNumber", 1, "priceCents", 4000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 21L);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentSucceeded(outcomeEventId, reservationId, "corr-dupe-21");
        producePaymentSucceeded(outcomeEventId, reservationId, "corr-dupe-21");

        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(outcomeEventId);
        outboxPublisher.poll();

        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationConfirmed'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
        Integer processedRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.processed_events WHERE event_id = ?",
                Integer.class, outcomeEventId);
        assertThat(processedRows).isEqualTo(1);
        assertThat(seatStatus(created.seatIds().get(0))).isEqualTo("SOLD");
    }

    private void producePaymentSucceeded(UUID eventId, long reservationId, String correlationId) throws Exception {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId,
                "payment.PaymentSucceeded",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of("paymentId", 1L, "reservationId", reservationId, "amountCents", 27000, "status", "SUCCEEDED"));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_EVENTS_TOPIC,
                new UUID(0L, reservationId).toString(),
                objectMapper.writeValueAsString(envelope));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
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

    private long postReservation(long eventId, List<Long> seatIds, long customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("eventId", eventId, "seatIds", seatIds), headers);
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/reservations", entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private String reservationStatus(long reservationId) {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/reservations/" + reservationId, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("status");
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
    }

    private void awaitReservationStatus(long reservationId, String status) {
        String last = "";
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            last = query(jdbc ->
                    "SELECT status FROM reservation.reservations WHERE id = " + reservationId);
            if (status.equals(last)) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError(
                "Timed out waiting for reservation " + reservationId + " to reach " + status
                        + " (last seen status: " + last
                        + "; processed_events rows: "
                        + count("SELECT count(*) FROM reservation.processed_events")
                        + "; outbox rows for reservation: "
                        + count("SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = "
                                + reservationId)
                        + "; seat states for reservation: "
                        + query(jdbc -> "SELECT string_agg(s.id || ':' || s.status, ', ') "
                                + "FROM reservation.reservation_seats rs "
                                + "JOIN reservation.seats s ON s.id = rs.seat_id "
                                + "WHERE rs.reservation_id = " + reservationId)
                        + ")");
    }

    private void awaitProcessed(UUID eventId) {
        awaitUntil(() -> count("SELECT count(*) FROM reservation.processed_events WHERE event_id = '"
                        + eventId + "'") > 0,
                "event " + eventId + " to be marked processed");
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String query(java.util.function.Function<JdbcTemplate, String> fn) {
        String value = fn.apply(jdbc);
        return value == null ? "" : value;
    }

    private ConsumerRecord<String, String> awaitOnTopic(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(topic)) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private long parseReservationId(String value) {
        try {
            JsonNode tree = objectMapper.readTree(value);
            return tree.path("payload").path("reservationId").asLong();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return -1L;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting async state", ex);
        }
    }

    private record CreatedEvent(long eventId, List<Long> seatIds) {}
}
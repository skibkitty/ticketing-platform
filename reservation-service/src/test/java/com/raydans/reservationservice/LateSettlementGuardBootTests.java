package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.reservation.HoldExpirer;
import com.raydans.reservationservice.web.ReservationController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * T09 end-to-end: the ADR 007 late-settlement guard, proven on the real expiry path. A
 * {@code PaymentSucceeded} settles just too late — the sweep has already marked the reservation
 * {@code EXPIRED}, released its seats, and a different customer has already re-held them. The
 * outcome must be claimed as processed and change nothing: the expired reservation stays expired,
 * the new holder keeps its seats (never sold out from under it, never released back to the pool),
 * and the record is consumed cleanly rather than becoming a poison message redelivered forever
 * (the failure ADR 007 exists to prevent, where a rolled-back {@code processed_events} insert
 * would re-deliver the same late outcome on every retry).
 *
 * <p>Distinct from the ADR 007 guard tests in the sibling confirmation/compensation classes: those
 * force the terminal status with direct SQL while the seat is still held by the same reservation,
 * whereas here the expiry is produced by {@link HoldExpirer} and the seats belong to somebody else,
 * which is the case where an unguarded confirm would actually re-flip live inventory.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LateSettlementGuardBootTests {

    static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";
    static final String PAYMENT_EVENTS_DLT = PAYMENT_EVENTS_TOPIC + ".dlt";
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
            admin.createTopics(List.of(
                            new NewTopic(PAYMENT_EVENTS_TOPIC, 1, (short) 1),
                            new NewTopic(PAYMENT_EVENTS_DLT, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to pre-create topics for the test broker", ex);
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
    void lateSucceededAfterSweepExpiryLeavesTheReservationExpiredAndDoesNotStealTheReHeldSeat()
            throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000),
                Map.of("section", "Orchestra", "row", "B", "seatNumber", 2, "priceCents", 12000)));
        long lateReservationId = postReservation(created.eventId(), created.seatIds(), 90L);
        long seatId = created.seatIds().get(0);

        // The hold lapses and the scheduled sweep — not SQL surgery — expires it, releasing the seats.
        lapseHold(lateReservationId, created.seatIds());
        holdExpirer.expire();
        assertThat(reservationStatus(lateReservationId)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");

        // A different customer buys the released seat while the money step is still in flight.
        long newHolderId = postReservation(created.eventId(), List.of(seatId), 91L);
        assertThat(reservationStatus(newHolderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        java.sql.Timestamp newHoldersHoldExpiry = seatHoldExpiresAt(seatId);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentSucceeded(outcomeEventId, lateReservationId, "corr-late-succeeded-" + lateReservationId);
        awaitProcessed(outcomeEventId);

        // The 10-minute hold is binding: the late money never revives the expired reservation.
        assertThat(reservationStatus(lateReservationId))
                .as("a PaymentSucceeded landing after the sweep must not confirm the expired reservation")
                .isEqualTo("EXPIRED");
        assertThat(outboxCount(lateReservationId, "reservation.ReservationConfirmed"))
                .as("the guarded outcome must publish nothing")
                .isZero();

        // The seat now belongs to the new holder, so an unguarded confirm would call markSold() on
        // it — silently taking a live hold and selling it to the wrong customer.
        assertThat(seatStatus(seatId))
                .as("the re-held seat must not be sold by another reservation's late outcome")
                .isEqualTo("HELD");
        assertThat(seatHoldExpiresAt(seatId))
                .as("the new holder's hold must be untouched (status and expiry alike)")
                .isEqualTo(newHoldersHoldExpiry);
        assertThat(reservationStatus(newHolderId))
                .as("the late outcome must not disturb the new holder's own pending reservation")
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void lateSucceededAfterSweepExpiryIsClaimedExactlyOnceAndNeverDeadLettered() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "A", "seatNumber", 1, "priceCents", 5000)));
        long lateReservationId = postReservation(created.eventId(), created.seatIds(), 92L);
        long seatId = created.seatIds().get(0);

        lapseHold(lateReservationId, created.seatIds());
        holdExpirer.expire();
        assertThat(reservationStatus(lateReservationId)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");

        // Control: an unsupported type IS dead-lettered, so the negative DLT assertion below is
        // about this outcome being handled — not about the DLT being unreadable in this context.
        UUID poisonEventId = UUID.randomUUID();
        produceUnsupportedOutcome(poisonEventId, lateReservationId, "corr-poison-control-" + lateReservationId);
        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT, record -> record.value().contains(poisonEventId.toString())))
                .as("the dead-letter path must be live, or the negative assertion below proves nothing")
                .isNotNull();

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentSucceeded(outcomeEventId, lateReservationId, "corr-late-noop-" + lateReservationId);
        awaitProcessed(outcomeEventId);

        // A redelivery of the same event is a no-op rather than a second attempt: the guard claims
        // the idempotency row and returns without touching state, so the claim survives the
        // transaction instead of rolling back into a redelivery loop. The fresh-id sentinel behind
        // it is ordered later on the same partition, so once it is claimed the duplicate has
        // provably been consumed too — no sleeping and hoping.
        producePaymentSucceeded(outcomeEventId, lateReservationId, "corr-late-noop-" + lateReservationId);
        UUID sentinelEventId = UUID.randomUUID();
        producePaymentSucceeded(sentinelEventId, lateReservationId, "corr-late-sentinel-" + lateReservationId);
        awaitProcessed(sentinelEventId);

        assertThat(processedCount(outcomeEventId))
                .as("the guarded outcome is claimed exactly once, however often it is delivered")
                .isEqualTo(1);
        assertThat(processedCount(sentinelEventId))
                .as("the sentinel is a distinct event, so it claims its own row")
                .isEqualTo(1);
        assertThat(reservationStatus(lateReservationId)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId))
                .as("neither the original delivery nor its redelivery may touch the released seat")
                .isEqualTo("AVAILABLE");
        assertThat(outboxCount(lateReservationId, "reservation.ReservationConfirmed"))
                .as("a guarded redelivered outcome publishes nothing either time")
                .isZero();
        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT, record -> record.value().contains(outcomeEventId.toString())))
                .as("a late settlement is a well-formed outcome that is handled, never a poison record")
                .isNull();
    }

    @Test
    void lateFailedAfterSweepExpiryLeavesTheReservationExpiredAndDoesNotReleaseTheReHeldSeat()
            throws Exception {
        // The compensating side of the same guard, and the outcome ADR 007 calls out by name: a
        // PaymentFailed arriving after expiry leaves the reservation EXPIRED, not CANCELLED, and
        // must not release a seat the late reservation no longer owns.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "A", "seatNumber", 1, "priceCents", 4000)));
        long lateReservationId = postReservation(created.eventId(), created.seatIds(), 93L);
        long seatId = created.seatIds().get(0);

        lapseHold(lateReservationId, created.seatIds());
        holdExpirer.expire();
        assertThat(reservationStatus(lateReservationId)).isEqualTo("EXPIRED");

        long newHolderId = postReservation(created.eventId(), List.of(seatId), 94L);
        assertThat(reservationStatus(newHolderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        java.sql.Timestamp newHoldersHoldExpiry = seatHoldExpiresAt(seatId);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, lateReservationId, "corr-late-failed-" + lateReservationId);
        awaitProcessed(outcomeEventId);

        assertThat(reservationStatus(lateReservationId))
                .as("a PaymentFailed after expiry leaves the reservation EXPIRED, not CANCELLED")
                .isEqualTo("EXPIRED");
        assertThat(outboxCount(lateReservationId, "reservation.ReservationCancelled")).isZero();
        assertThat(seatStatus(seatId))
                .as("a late compensation must not hand another holder's seat back to the pool")
                .isEqualTo("HELD");
        assertThat(seatHoldExpiresAt(seatId))
                .as("the new holder's hold expiry must be untouched")
                .isEqualTo(newHoldersHoldExpiry);
        assertThat(reservationStatus(newHolderId))
                .as("the new holder's own reservation is unaffected")
                .isEqualTo("PENDING_PAYMENT");
    }

    private void lapseHold(long reservationId, List<Long> seatIds) {
        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        for (long seatId : seatIds) {
            jdbc.update(
                    "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?",
                    seatId);
        }
    }

    private void producePaymentSucceeded(UUID eventId, long reservationId, String correlationId)
            throws Exception {
        produceOutcome(eventId, reservationId, "payment.PaymentSucceeded", "SUCCEEDED", 1L, correlationId);
    }

    private void producePaymentFailed(UUID eventId, long reservationId, String correlationId)
            throws Exception {
        produceOutcome(eventId, reservationId, "payment.PaymentFailed", "FAILED", 2L, correlationId);
    }

    /**
     * A well-formed envelope carrying a type the outcome consumer deliberately rejects. It exists
     * only as the positive control that proves the dead-letter path is live in this test context,
     * so the "never dead-lettered" assertion elsewhere is not passing vacuously.
     */
    private void produceUnsupportedOutcome(UUID eventId, long reservationId, String correlationId)
            throws Exception {
        produceOutcome(eventId, reservationId, "payment.PaymentMaybeLater", "PENDING", 3L, correlationId);
    }

    private void produceOutcome(
            UUID eventId, long reservationId, String eventType, String status, long paymentId,
            String correlationId) throws Exception {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId,
                eventType,
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of("paymentId", paymentId, "reservationId", reservationId, "amountCents", 27000, "status",
                        status));
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

    private java.sql.Timestamp seatHoldExpiresAt(long seatId) {
        return jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", java.sql.Timestamp.class, seatId);
    }

    private int outboxCount(long reservationId, String eventType) {
        return count("SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = " + reservationId
                + " AND event_type = '" + eventType + "'");
    }

    private int processedCount(UUID eventId) {
        return count("SELECT count(*) FROM reservation.processed_events WHERE event_id = '" + eventId + "'");
    }

    private void awaitProcessed(UUID eventId) {
        awaitUntil(() -> processedCount(eventId) > 0, "event " + eventId + " to be marked processed");
    }

    private void awaitUntil(BooleanSupplier condition, String what) {
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

    private ConsumerRecord<String, String> awaitOnTopic(
            String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(topic)) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2)).records(topic)) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, String> kafkaConsumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
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

package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxPublisher;
import com.raydans.reservationservice.reservation.ReservationConfirmationService;
import com.raydans.reservationservice.web.ReservationController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
        // Explicit provisioning instead of relying on broker auto-creation: the same layout
        // KafkaTopicConfig provisions for the app (NewTopic beans via KafkaAdmin).
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
    OutboxPublisher outboxPublisher;

    @Autowired
    ReservationConfirmationService confirmation;

    @MockitoSpyBean
    SeatRepository seats;

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
                record -> reservationId == parseReservationId(record.value())
                        && record.value().contains("reservation.ReservationConfirmed"));
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

    @Test
    void twoDistinctPaymentSucceededEventsViaKafkaStillConfirmExactlyOnce() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Parterre", "row", "A", "seatNumber", 1, "priceCents", 6000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 77L);

        // Two DIFFERENT event ids for the same reservation. Sequential at the broker proves
        // the invariant end-to-end; the true overlap is forced in the concurrency test below.
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        producePaymentSucceeded(eventA, reservationId, "corr-two-a-" + reservationId);
        producePaymentSucceeded(eventB, reservationId, "corr-two-b-" + reservationId);

        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(eventA);
        awaitProcessed(eventB);
        outboxPublisher.poll();

        assertThat(outboxConfirmedCount(reservationId)).as("one confirmation regardless of distinct event ids")
                .isEqualTo(1);
        assertThat(seatStatus(created.seatIds().get(0))).isEqualTo("SOLD");
    }

    @Test
    void concurrentDistinctEventsForSameReservationConfirmExactlyOnce() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Grand Tier", "row", "A", "seatNumber", 1, "priceCents", 9000),
                Map.of("section", "Grand Tier", "row", "B", "seatNumber", 2, "priceCents", 8000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 33L);

        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        EventEnvelope<JsonNode> envA = succeededEnvelopeJson(eventA, reservationId, "corr-race-a-" + reservationId);
        EventEnvelope<JsonNode> envB = succeededEnvelopeJson(eventB, reservationId, "corr-race-b-" + reservationId);

        // The Kafka listener is single-threaded, so the true race lives here — many
        // transactions racing the same PENDING reservation through the same service bean
        // (same technique as payment-service's concurrency tests). With the ADR 006
        // optimistic lock at most one transaction commits; a loser rolls back its claim
        // and surfaces an ObjectOptimisticLockingFailureException.
        List<Throwable> raceFailures = runConcurrentlyReturningFailures(List.of(
                () -> confirmation.process(envA, "corr-race-a"),
                () -> confirmation.process(envA, "corr-race-a"),
                () -> confirmation.process(envA, "corr-race-a"),
                () -> confirmation.process(envB, "corr-race-b"),
                () -> confirmation.process(envB, "corr-race-b"),
                () -> confirmation.process(envB, "corr-race-b")));

        awaitReservationStatus(reservationId, "CONFIRMED");

        // A loser rolled back its idempotency claim with the business transaction. Kafka
        // at-least-once then redelivers: each eventId must now be a clean duplicate-guard
        // no-op — never a second confirmation, never an error.
        List<Throwable> redeliveryFailures = new ArrayList<>();
        for (UUID eventId : List.of(eventA, eventB)) {
            try {
                confirmation.process(succeededEnvelopeJson(eventId, reservationId, "corr-redeliver-" + eventId), "corr-redeliver");
            } catch (Throwable t) {
                redeliveryFailures.add(t);
            }
            awaitProcessed(eventId);
        }

        outboxPublisher.poll();
        assertThat(redeliveryFailures)
                .as("redelivery of both event ids after the race must be a clean no-op")
                .isEmpty();
        assertThat(raceFailures)
                .as("a concurrent loser may throw exactly an optimistic-lock exception, never a double-confirm")
                .allMatch(failure -> failure instanceof ObjectOptimisticLockingFailureException);
        assertThat(outboxConfirmedCount(reservationId)).isEqualTo(1);
        for (long seatId : created.seatIds()) {
            assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        }
    }

    @Test
    void failingRecordIsDeadLetteredAfterRetriesAndConsumerKeepsWorking() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Box", "row", "A", "seatNumber", 1, "priceCents", 5000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 55L);

        // Intercept the seat write INSIDE the confirmation transaction (after the idempotency
        // claim) so every attempt fails and retries/exhaustion is exercised end to end.
        doThrow(new RuntimeException("poison seat write"))
                .when(seats).saveAll(anyList());

        UUID poisonEventId = UUID.randomUUID();
        producePaymentSucceeded(poisonEventId, reservationId, "corr-poison-55");

        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT,
                record -> record.value().contains(poisonEventId.toString())))
                .as("a record failing on every attempt must land on " + PAYMENT_EVENTS_DLT
                        + " after the configured retry count")
                .isNotNull();

        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(created.seatIds().get(0))).isEqualTo("HELD");
        assertThat(processedCount(poisonEventId)).isZero();
        assertThat(outboxConfirmedCount(reservationId)).isZero();

        // The consumer is not wedged: a valid event afterwards still confirms.
        reset(seats);
        UUID validEventId = UUID.randomUUID();
        producePaymentSucceeded(validEventId, reservationId, "corr-after-dlt-55");
        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(validEventId);
    }

    @Test
    void failedTransactionRollsBackTheClaimAndRedeliverySucceeds() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Dress Circle", "row", "A", "seatNumber", 1, "priceCents", 7000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 66L);
        long seatId = created.seatIds().get(0);

        // Fail the first two attempts AFTER the claim, then let the third succeed: the retry
        // proves the claim was rolled back with the business transaction and can be re-taken.
        // saveAll is abstract on the repository proxy (Mockito can't call it "for real"), so
        // the third attempt just returns normally — the seat sale still lands because the
        // seat entities are managed and persisting by Hibernate's commit-time flush.
        AtomicInteger attempt = new AtomicInteger();
        CountDownLatch firstFailure = new CountDownLatch(1);
        doAnswer(invocation -> {
                    if (attempt.incrementAndGet() <= 2) {
                        firstFailure.countDown();
                        throw new RuntimeException("boom after claim");
                    }
                    return invocation.getArguments()[0];
                })
                .when(seats).saveAll(anyList());

        UUID eventId = UUID.randomUUID();
        producePaymentSucceeded(eventId, reservationId, "corr-rollback-66");

        assertThat(firstFailure.await(30, TimeUnit.SECONDS))
                .as("the first confirmation attempt failed after the idempotency claim")
                .isTrue();

        // Immediately after that failing attempt the whole transaction is rolled back.
        assertThat(processedCount(eventId)).as("idempotency claim rollback").isZero();
        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");

        // Kafka redelivers the same event (DefaultErrorHandler): once the write works the
        // event confirms and is then claimed exactly once.
        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(eventId);
        assertThat(outboxConfirmedCount(reservationId)).isEqualTo(1);
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");

        // Leave the shared @MockitoSpyBean clean for the other tests in this cached context.
        reset(seats);
    }

    @Test
    void unknownEventTypeIsRejectedAndDeadLetteredNotSilentlyDropped() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Galleries", "row", "A", "seatNumber", 1, "priceCents", 4500)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 88L);

        UUID weirdEventId = UUID.randomUUID();
        produceEnvelope(new EventEnvelope<>(weirdEventId, "pipeline.UnknownThing", Instant.now(),
                "corr-weird-88", new UUID(0L, reservationId), Map.of("whatever", 1)), reservationId);

        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT,
                record -> record.value().contains("pipeline.UnknownThing")))
                .as("an unrecognized event type must be rejected and dead-lettered, not silently acked")
                .isNotNull();

        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(created.seatIds().get(0))).isEqualTo("HELD");
        assertThat(processedCount(weirdEventId)).isZero();
        assertThat(outboxConfirmedCount(reservationId)).isZero();

        UUID validEventId = UUID.randomUUID();
        producePaymentSucceeded(validEventId, reservationId, "corr-after-weird-88");
        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(validEventId);
    }

    @Test
    void paymentFailedIsIntentionallyIgnoredAndNeverDeadLettered() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Front Orchestra", "row", "A", "seatNumber", 1, "priceCents", 3000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 11L);

        UUID failEventId = UUID.randomUUID();
        produceEnvelope(new EventEnvelope<>(failEventId, "payment.PaymentFailed", Instant.now(),
                "corr-failed-11", new UUID(0L, reservationId),
                Map.of("paymentId", 2L, "reservationId", reservationId, "amountCents", 3000, "status", "FAILED")),
                reservationId);

        // Wait out the full retry/DLT window: a PaymentFailed must be ignored, not quarantined.
        assertThat(countMatchingWithin(PAYMENT_EVENTS_DLT,
                record -> record.value().contains(failEventId.toString()), Duration.ofSeconds(12)))
                .as("PaymentFailed is owned by T07; must not be dead-lettered")
                .isZero();
        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(created.seatIds().get(0))).isEqualTo("HELD");
        assertThat(processedCount(failEventId)).isZero();
        assertThat(outboxConfirmedCount(reservationId)).isZero();

        UUID okEventId = UUID.randomUUID();
        producePaymentSucceeded(okEventId, reservationId, "corr-after-failed-11");
        awaitReservationStatus(reservationId, "CONFIRMED");
        awaitProcessed(okEventId);
    }

    private void producePaymentSucceeded(UUID eventId, long reservationId, String correlationId) throws Exception {
        produceEnvelope(succeededEnvelope(eventId, reservationId, correlationId), reservationId);
    }

    private EventEnvelope<Map<String, Object>> succeededEnvelope(UUID eventId, long reservationId, String correlationId) {
        return new EventEnvelope<>(
                eventId,
                "payment.PaymentSucceeded",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                outcomePayload(reservationId));
    }

    private EventEnvelope<JsonNode> succeededEnvelopeJson(UUID eventId, long reservationId, String correlationId) {
        return new EventEnvelope<>(
                eventId,
                "payment.PaymentSucceeded",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                objectMapper.valueToTree(outcomePayload(reservationId)));
    }

    private Map<String, Object> outcomePayload(long reservationId) {
        return Map.of("paymentId", 1L, "reservationId", reservationId, "amountCents", 27000, "status", "SUCCEEDED");
    }

    private void produceEnvelope(EventEnvelope<Map<String, Object>> envelope, long reservationId) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_EVENTS_TOPIC,
                new UUID(0L, reservationId).toString(),
                objectMapper.writeValueAsString(envelope));
        if (envelope.correlationId() != null) {
            record.headers().add(CorrelationIdFilter.HEADER_NAME, envelope.correlationId().getBytes(StandardCharsets.UTF_8));
        }
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
            last = reservationStatus(reservationId);
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
                        + jdbc.queryForObject(
                                "SELECT string_agg(s.id || ':' || s.status, ', ') "
                                        + "FROM reservation.reservation_seats rs "
                                        + "JOIN reservation.seats s ON s.id = rs.seat_id "
                                        + "WHERE rs.reservation_id = ?",
                                String.class, reservationId)
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

    private int processedCount(UUID eventId) {
        return count("SELECT count(*) FROM reservation.processed_events WHERE event_id = '" + eventId + "'");
    }

    private int outboxConfirmedCount(long reservationId) {
        return count("SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = " + reservationId
                + " AND event_type = 'reservation.ReservationConfirmed'");
    }

    private ConsumerRecord<String, String> awaitOnTopic(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(topic)) {
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

    private long countMatchingWithin(String topic, Predicate<ConsumerRecord<String, String>> match, Duration window) {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(topic)) {
            Instant deadline = Instant.now().plus(window);
            long matching = 0;
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1)).records(topic)) {
                    if (match.test(record)) {
                        matching++;
                    }
                }
            }
            return matching;
        }
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

    /**
     * Runs the tasks (each usually a {@code confirmation.process} call) concurrently against
     * the real Spring bean and returns the collected exceptions. With optimistic locking on the
     * reservation, a loser is EXPECTED to throw once while the concurrent race settles; the
     * caller then retries it to prove the no-op path — unlike payment-service's variant, which
     * asserts no exceptions because its loser is a pure atomic-claim no-op.
     */
    private List<Throwable> runConcurrentlyReturningFailures(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("start barrier never released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted waiting for start barrier", ex);
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        try {
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads started").isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
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
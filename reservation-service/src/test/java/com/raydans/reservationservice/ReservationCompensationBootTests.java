package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T07 end-to-end: the compensating action. A {@code PaymentFailed} for a {@code PENDING_PAYMENT}
 * reservation cancels it, releases its seats back to {@code AVAILABLE} (hold expired cleared), and
 * publishes {@code ReservationCancelled} — so a declined customer's Seats go back on sale and the
 * next customer can hold them. The ADR 007 guard and ADR 004 idempotency are proven alongside.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationCompensationBootTests {

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
                    .all().get(30, java.util.concurrent.TimeUnit.SECONDS);
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
    void paymentFailedCancelsReservationReleasesSeatsAndPublishesReservationCancelled()
            throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000),
                Map.of("section", "Orchestra", "row", "B", "seatNumber", 2, "priceCents", 12000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 99L);
        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");

        UUID outcomeEventId = UUID.randomUUID();
        String correlationId = "corr-compensate-" + reservationId;
        producePaymentFailed(outcomeEventId, reservationId, correlationId);

        awaitReservationStatus(reservationId, "CANCELLED");
        for (long seatId : created.seatIds()) {
            assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
            assertThat(seatHoldExpiresAt(seatId)).as("hold expiry cleared for seat %s", seatId).isNull();
        }
        awaitProcessed(outcomeEventId);

        outboxPublisher.poll();

        ConsumerRecord<String, String> cancelled = awaitOnTopic(OutboxPublisher.RESERVATION_EVENTS_TOPIC,
                record -> reservationId == parseReservationId(record.value())
                        && record.value().contains("reservation.ReservationCancelled"));
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.key()).isEqualTo(new UUID(0L, reservationId).toString());
        assertThat(cancelled.value())
                .contains("\"eventType\":\"reservation.ReservationCancelled\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":99")
                .contains("\"eventId\":" + created.eventId())
                .contains("\"amountCents\":27000");
        assertThat(new String(cancelled.headers().lastHeader(CorrelationIdFilter.HEADER_NAME).value()))
                .isEqualTo(correlationId);

        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationCancelled'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    void releasedSeatsGoBackOnSaleAndANewCustomerCanHoldThem() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "A", "seatNumber", 1, "priceCents", 5000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 7L);
        long seatId = created.seatIds().get(0);

        producePaymentFailed(UUID.randomUUID(), reservationId, "corr-release-7");
        awaitReservationStatus(reservationId, "CANCELLED");
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");

        long secondReservationId = postReservation(created.eventId(), created.seatIds(), 8L);
        assertThat(secondReservationId).isNotEqualTo(reservationId);
        assertThat(reservationStatus(secondReservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
    }

    @Test
    void failedOutcomeForReservationNoLongerPendingIsANoOpMarkedProcessed() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "A", "seatNumber", 1, "priceCents", 4000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 21L);
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET status = 'EXPIRED' WHERE id = ?", reservationId);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, reservationId, "corr-guard-21");

        awaitProcessed(outcomeEventId);
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationCancelled'",
                Integer.class, reservationId);
        assertThat(outboxRows).isZero();
    }

    @Test
    void duplicatePaymentFailedDeliveryDoesNotDoubleCancel() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Parterre", "row", "A", "seatNumber", 1, "priceCents", 6000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 77L);
        long seatId = created.seatIds().get(0);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, reservationId, "corr-dupe-77");
        producePaymentFailed(outcomeEventId, reservationId, "corr-dupe-77");

        awaitReservationStatus(reservationId, "CANCELLED");
        awaitProcessed(outcomeEventId);
        outboxPublisher.poll();

        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationCancelled'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
        Integer processedRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.processed_events WHERE event_id = ?",
                Integer.class, outcomeEventId);
        assertThat(processedRows).isEqualTo(1);
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
    }

    @Test
    void paymentFailedIsCompensatedNotBlackHoledOrDeadLettered() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Box", "row", "A", "seatNumber", 1, "priceCents", 5000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 55L);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, reservationId, "corr-not-black-holed-55");

        // The compensating action lands AND the record never reaches the dead-letter topic:
        // PaymentFailed is a known, well-formed type that is handled, not quarantined
        // (ADR 008) and not silently swallowed.
        awaitReservationStatus(reservationId, "CANCELLED");
        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT, record -> record.value().contains(outcomeEventId.toString())))
                .as("PaymentFailed must be compensated end to end, never dead-lettered")
                .isNull();
    }

    @Test
    void paymentFailedForUnknownReservationIsDeadLetteredAndNeverConsumed() throws Exception {
        long ghostReservationId = 999_999L;
        UUID outcomeEventId = UUID.randomUUID();
        String correlationId = "corr-ghost-999999";
        producePaymentFailed(outcomeEventId, ghostReservationId, correlationId);

        // Every retry claims the idempotency row, then throws on the unknown reservation — and the
        // failed tx rolls the claim back. After the retry budget the poison lands on the DLT, so an
        // unresolvable PaymentFailed is quarantined instead of blocking the consumer.
        assertThat(awaitOnTopic(PAYMENT_EVENTS_DLT, record -> record.value().contains(outcomeEventId.toString())))
                .as("an unknown-reservation PaymentFailed must be retried then dead-lettered")
                .isNotNull();
        // The record is dead-lettered, never marked processed: every attempt's idempotency claim
        // rolls back with the failing transaction, so the poison is never silently consumed.
        assertThat(processedCount(outcomeEventId))
                .as("each failed attempt's idempotency claim must be rolled back")
                .isZero();
        Integer cancelledRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationCancelled'",
                Integer.class, ghostReservationId);
        assertThat(cancelledRows).isZero();
    }

    @Test
    void failedCompensationRollsBackTheClaimAndRedeliverySucceeds() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Dress Circle", "row", "A", "seatNumber", 1, "priceCents", 7000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 66L);
        long seatId = created.seatIds().get(0);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstFailure = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 2) {
                firstFailure.countDown();
                throw new RuntimeException("boom during compensation");
            }
            return invocation.getArguments()[0];
        }).when(seats).saveAll(anyList());

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, reservationId, "corr-compensation-rollback-66");

        assertThat(firstFailure.await(30, TimeUnit.SECONDS))
                .as("the first compensation attempt failed after the idempotency claim")
                .isTrue();

        // The failed transaction rolled back in full: a mid-compensation crash does not strand the
        // reservation as partially cancelled nor leak a claim / cancelled outbox row.
        assertThat(processedCount(outcomeEventId)).as("idempotency claim must roll back").isZero();
        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        Integer cancelledRows = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationCancelled'",
                Integer.class, reservationId);
        assertThat(cancelledRows).isZero();

        // Kafka redelivers; once the seat write works the compensation lands exactly once.
        awaitReservationStatus(reservationId, "CANCELLED");
        awaitProcessed(outcomeEventId);
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(outboxCancelledCount(reservationId)).isEqualTo(1);

        reset(seats);
    }

    @Test
    void concurrentSucceededAndFailedOutcomesLeaveNoInconsistentState() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Grand Tier", "row", "A", "seatNumber", 1, "priceCents", 9000),
                Map.of("section", "Grand Tier", "row", "B", "seatNumber", 2, "priceCents", 8000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 33L);

        UUID okEvent1 = UUID.randomUUID();
        UUID failEvent1 = UUID.randomUUID();
        UUID okEvent2 = UUID.randomUUID();
        UUID failEvent2 = UUID.randomUUID();
        EventEnvelope<JsonNode> okEnv1 = succeededEnvelopeJson(okEvent1, reservationId, "corr-race-33");
        EventEnvelope<JsonNode> failEnv1 = failedEnvelopeJson(failEvent1, reservationId, "corr-race-33");
        EventEnvelope<JsonNode> okEnv2 = succeededEnvelopeJson(okEvent2, reservationId, "corr-race-33");
        EventEnvelope<JsonNode> failEnv2 = failedEnvelopeJson(failEvent2, reservationId, "corr-race-33");

        List<Throwable> raceFailures = runConcurrentlyReturningFailures(List.of(
                buildProcessor(okEnv1),
                buildProcessor(failEnv1),
                buildProcessor(okEnv2),
                buildProcessor(failEnv2)));

        // Exactly one terminal transition wins the single-version gate: either confirmed+sold or
        // cancelled+available — never a mix. Losers may throw an optimistic-lock exception as the
        // version conflict rolls back their whole tx (including their idempotency claim).
        assertThat(raceFailures)
                .allSatisfy(failure -> {
                    assertThat(failure)
                            .as("the only legitimate race failure is the optimistic-lock guard")
                            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
                });

        String terminalStatus = awaitTerminalStatus(reservationId);
        boolean confirmed = "CONFIRMED".equals(terminalStatus);
        assertThat(confirmed || "CANCELLED".equals(terminalStatus))
                .as("reservation must reach exactly one terminal state, was " + terminalStatus)
                .isTrue();
        for (long seatId : created.seatIds()) {
            assertThat(seatStatus(seatId))
                    .as("seat state must agree with the winning terminal transition")
                    .isEqualTo(confirmed ? "SOLD" : "AVAILABLE");
        }

        // Both losing paths redeliver cleanly: the winner's duplicate is skipped by the idempotency
        // claim, the loser's event is claimed once and no-ops on the ADR 007 guard. All four events
        // are distinct, so each is processed exactly once.
        List<EventEnvelope<JsonNode>> allOutcomes = List.of(okEnv1, failEnv1, okEnv2, failEnv2);
        List<Throwable> redeliveryFailures = new ArrayList<>();
        for (EventEnvelope<JsonNode> env : allOutcomes) {
            try {
                confirmation.process(env, "corr-redeliver-33-" + env.eventId());
            } catch (Throwable throwable) {
                redeliveryFailures.add(throwable);
            }
        }
        for (UUID eventId : List.of(okEvent1, failEvent1, okEvent2, failEvent2)) {
            awaitProcessed(eventId);
        }

        assertThat(redeliveryFailures).as("redelivered losers must be no-ops, not errors").isEmpty();
        assertThat(processedCount(okEvent1)).isEqualTo(1);
        assertThat(processedCount(failEvent1)).isEqualTo(1);
        assertThat(processedCount(okEvent2)).isEqualTo(1);
        assertThat(processedCount(failEvent2)).isEqualTo(1);

        // Exactly one terminal event is emitted — the confirm and cancel outbox rows are mutually
        // exclusive and sum to one, so the race can never publish both (or neither) transitions.
        int confirmedCount = outboxConfirmedCount(reservationId);
        int cancelledCount = outboxCancelledCount(reservationId);
        assertThat(confirmedCount)
                .as("a confirmed reservation must emit exactly its one ReservationConfirmed row")
                .isEqualTo(confirmed ? 1 : 0);
        assertThat(cancelledCount)
                .as("a cancelled reservation must emit exactly its one ReservationCancelled row")
                .isEqualTo(confirmed ? 0 : 1);
        assertThat(confirmedCount + cancelledCount)
                .as("the race must emit exactly one terminal outbox event")
                .isEqualTo(1);
    }

    @Test
    void paymentFailedAfterConfirmationIsANoOpMarkedProcessed() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Circle", "row", "A", "seatNumber", 1, "priceCents", 3000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 11L);
        long seatId = created.seatIds().get(0);

        // Force the reservation straight to CONFIRMED with its seat sold, as if the money step
        // confirmed it first — a late PaymentFailed must not claw already-sold seats back.
        jdbc.update(
                "UPDATE reservation.reservations SET status = 'CONFIRMED' WHERE id = ?", reservationId);
        jdbc.update(
                "UPDATE reservation.seats SET status = 'SOLD', hold_expires_at = NULL WHERE id = ?", seatId);

        UUID outcomeEventId = UUID.randomUUID();
        producePaymentFailed(outcomeEventId, reservationId, "corr-guard-confirmed-11");

        awaitProcessed(outcomeEventId);
        assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        assertThat(outboxCancelledCount(reservationId)).isZero();
    }

    @Test
    void cancellationAmountComesFromReservationSeatPricingNotTheEventsAmountCents() throws Exception {
        // Invariant: ReservationCancelled.amountCents is derived from the reservation's own seat
        // pricing (4000), never from the PaymentFailed event's amountCents (27000) — the event
        // amount is informational to reservation-service and is not the source of truth.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 1, "priceCents", 4000)));
        long reservationId = postReservation(created.eventId(), created.seatIds(), 5L);
        long seatId = created.seatIds().get(0);

        UUID outcomeEventId = UUID.randomUUID();
        String correlationId = "corr-amount-truth-" + reservationId;
        producePaymentFailed(outcomeEventId, reservationId, 27_000, correlationId);

        awaitReservationStatus(reservationId, "CANCELLED");
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        awaitProcessed(outcomeEventId);

        outboxPublisher.poll();

        ConsumerRecord<String, String> cancelled = awaitOnTopic(OutboxPublisher.RESERVATION_EVENTS_TOPIC,
                record -> reservationId == parseReservationId(record.value())
                        && record.value().contains("reservation.ReservationCancelled"));
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.value())
                .as("the cancellation amount is the reservation's seat-pricing truth (4000), "
                        + "never the PaymentFailed event's amountCents (27000)")
                .contains("\"amountCents\":4000")
                .doesNotContain("\"amountCents\":27000");
    }

    private void producePaymentFailed(UUID eventId, long reservationId, String correlationId) throws Exception {
        producePaymentFailed(eventId, reservationId, 27_000, correlationId);
    }

    private void producePaymentFailed(UUID eventId, long reservationId, int amountCents, String correlationId)
            throws Exception {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId,
                "payment.PaymentFailed",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of("paymentId", 2L, "reservationId", reservationId, "amountCents", amountCents, "status", "FAILED"));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_EVENTS_TOPIC,
                new UUID(0L, reservationId).toString(),
                objectMapper.writeValueAsString(envelope));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private EventEnvelope<JsonNode> succeededEnvelopeJson(UUID eventId, long reservationId, String correlationId) {
        return new EventEnvelope<>(
                eventId, "payment.PaymentSucceeded", Instant.now(), correlationId,
                new UUID(0L, reservationId), objectMapper.valueToTree(Map.of(
                        "paymentId", 1L, "reservationId", reservationId, "amountCents", 27000, "status", "SUCCEEDED")));
    }

    private EventEnvelope<JsonNode> failedEnvelopeJson(UUID eventId, long reservationId, String correlationId) {
        return new EventEnvelope<>(
                eventId, "payment.PaymentFailed", Instant.now(), correlationId,
                new UUID(0L, reservationId), objectMapper.valueToTree(Map.of(
                        "paymentId", 2L, "reservationId", reservationId, "amountCents", 27000, "status", "FAILED")));
    }

    private Runnable buildProcessor(EventEnvelope<JsonNode> envelope) {
        return () -> {
            try {
                confirmation.process(envelope, envelope.correlationId());
            } catch (Error error) {
                throw error;
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        };
    }

    private List<Throwable> runConcurrentlyReturningFailures(List<Runnable> tasks) throws Exception {
        List<Future<Void>> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            for (Runnable task : tasks) {
                results.add(pool.submit(() -> {
                    try {
                        task.run();
                        return null;
                    } catch (Throwable throwable) {
                        failures.add(throwable);
                        return null;
                    }
                }));
            }
            for (Future<Void> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    private String awaitTerminalStatus(long reservationId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            String status = reservationStatus(reservationId);
            if ("CONFIRMED".equals(status) || "CANCELLED".equals(status)) {
                return status;
            }
            sleep(100);
        }
        throw new AssertionError(
                "Timed out waiting for reservation " + reservationId + " to reach a terminal state (last seen: "
                        + reservationStatus(reservationId) + ")");
    }

    private int processedCount(UUID eventId) {
        return count(
                "SELECT count(*) FROM reservation.processed_events WHERE event_id = '" + eventId + "'");
    }

    private int outboxCancelledCount(long reservationId) {
        return count(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = "
                        + reservationId + " AND event_type = 'reservation.ReservationCancelled'");
    }

    private int outboxConfirmedCount(long reservationId) {
        return count(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = "
                        + reservationId + " AND event_type = 'reservation.ReservationConfirmed'");
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
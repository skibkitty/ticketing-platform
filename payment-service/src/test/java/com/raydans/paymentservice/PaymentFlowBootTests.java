package com.raydans.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.paymentservice.outbox.OutboxPublisher;
import com.raydans.paymentservice.payment.PaymentProcessingService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentFlowBootTests {

    static final String RESERVATION_EVENTS_TOPIC = "reservation.events.v1";
    static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxPublisher outboxPublisher;

    @Autowired
    PaymentProcessingService paymentProcessing;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.outbox.poll-interval-ms", () -> "60000");
        // Not the yml default (50000): proves the env-overridable threshold decides the outcome.
        registry.add("app.payment.decline-threshold-cents", () -> "45000");
    }

    @Test
    void lowAmountReservationCreatesSucceededPaymentAndPublishesOutcomeEvent() throws Exception {
        long reservationId = 101L;
        UUID eventId = UUID.randomUUID();
        String correlationId = "corr-succeed-101";
        produceReservationCreated(eventId, reservationId, 44000, correlationId);

        awaitPaymentStatus(reservationId, "SUCCEEDED");
        awaitProcessed(eventId);
        outboxPublisher.poll();

        ConsumerRecord<String, String> outcome = awaitOutcome(reservationId, "payment.PaymentSucceeded");
        assertThat(outcome.key()).isEqualTo(new UUID(0L, reservationId).toString());
        assertThat(new String(outcome.headers().lastHeader(CorrelationIdFilter.HEADER_NAME).value()))
                .isEqualTo(correlationId);
        assertThat(outcome.value())
                .contains("\"eventType\":\"payment.PaymentSucceeded\"")
                .contains("\"aggregateId\":\"" + new UUID(0L, reservationId) + "\"")
                .contains("\"correlationId\":\"" + correlationId + "\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"amountCents\":44000")
                .contains("\"status\":\"SUCCEEDED\"");

        assertSinglePayment(reservationId, "SUCCEEDED", 44000);
        assertProcessedOnce(eventId);
    }

    @Test
    void highAmountReservationCreatesFailedPaymentAndPublishesOutcomeEvent() throws Exception {
        long reservationId = 102L;
        UUID eventId = UUID.randomUUID();
        String correlationId = "corr-fail-102";
        produceReservationCreated(eventId, reservationId, 45000, correlationId);

        awaitPaymentStatus(reservationId, "FAILED");
        awaitProcessed(eventId);
        outboxPublisher.poll();

        ConsumerRecord<String, String> outcome = awaitOutcome(reservationId, "payment.PaymentFailed");
        assertThat(outcome.key()).isEqualTo(new UUID(0L, reservationId).toString());
        assertThat(outcome.value())
                .contains("\"eventType\":\"payment.PaymentFailed\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"amountCents\":45000")
                .contains("\"status\":\"FAILED\"");

        assertSinglePayment(reservationId, "FAILED", 45000);
        assertProcessedOnce(eventId);
    }

    @Test
    void sequentialDuplicateDeliveryProducesSinglePaymentAndSingleOutcomeEvent() throws Exception {
        long reservationId = 103L;
        UUID eventId = UUID.randomUUID();
        produceReservationCreated(eventId, reservationId, 44000, "corr-dupe-103");
        produceReservationCreated(eventId, reservationId, 44000, "corr-dupe-103");

        awaitPaymentStatus(reservationId, "SUCCEEDED");
        awaitProcessed(eventId);
        assertNotDeadLettered(eventId);
        outboxPublisher.poll();

        assertSinglePayment(reservationId, "SUCCEEDED", 44000);
        assertProcessedOnce(eventId);
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ? AND event_type = 'payment.PaymentSucceeded'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
        // This only counts one publish in THIS test run. The outbox is at-least-once
        // (ADR 003): a crash between the Kafka send and the published_at commit would
        // re-publish the same eventId, which is why consumers dedupe on it (ADR 004).
        assertOutcomePublishedOnceThisRun(reservationId);
    }

    @Test
    void concurrentDuplicateDeliveryOfTheSameEventIsAnIdempotentNoOp() throws Exception {
        long reservationId = 300L;
        UUID eventId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = reservationCreatedEnvelope(eventId, reservationId, 44000, "corr-race-300");

        // The consumer listener is single-threaded, so the true race lives here: many
        // transactions racing to claim the same eventId through the same service bean.
        runConcurrently(6, () -> paymentProcessing.process(envelope, "corr-race-300"));

        assertSinglePayment(reservationId, "SUCCEEDED", 44000);
        assertProcessedOnce(eventId);
        assertSameEventOutboxRows(reservationId, 1);

        outboxPublisher.poll();
        assertThat(awaitOutcome(reservationId, "payment.PaymentSucceeded")).isNotNull();
        assertOutcomePublishedOnceThisRun(reservationId);
    }

    @Test
    void concurrentDifferentEventsForTheSameReservationProduceOnePaymentAndNoException() throws Exception {
        long reservationId = 301L;
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        EventEnvelope<JsonNode> first = reservationCreatedEnvelope(firstEventId, reservationId, 44000, "corr-race-301a");
        EventEnvelope<JsonNode> second = reservationCreatedEnvelope(secondEventId, reservationId, 44000, "corr-race-301b");

        runConcurrently(List.of(
                () -> paymentProcessing.process(first, "corr-race-301a"),
                () -> paymentProcessing.process(first, "corr-race-301a"),
                () -> paymentProcessing.process(first, "corr-race-301a"),
                () -> paymentProcessing.process(first, "corr-race-301a"),
                () -> paymentProcessing.process(second, "corr-race-301b"),
                () -> paymentProcessing.process(second, "corr-race-301b"),
                () -> paymentProcessing.process(second, "corr-race-301b"),
                () -> paymentProcessing.process(second, "corr-race-301b")));

        // Exactly one payment and one outcome, but BOTH eventIds are recorded as processed:
        // a different eventId for the same reservation is a deterministic no-op, never a
        // uniqueness exception that would be retried and dead-lettered.
        assertSinglePayment(reservationId, "SUCCEEDED", 44000);
        assertProcessedOnce(firstEventId);
        assertProcessedOnce(secondEventId);
        assertSameEventOutboxRows(reservationId, 1);
    }

    @Test
    void twoConcurrentPublisherPollsNeverPublishTheSameRowTwice() throws Exception {
        UUID ev1 = UUID.randomUUID();
        UUID ev2 = UUID.randomUUID();
        UUID ev3 = UUID.randomUUID();
        insertOutboxRow(ev1, 400L, "payment.PaymentSucceeded", "{\"reservationId\":400,\"amountCents\":100,\"status\":\"SUCCEEDED\"}");
        insertOutboxRow(ev2, 401L, "payment.PaymentSucceeded", "{\"reservationId\":401,\"amountCents\":100,\"status\":\"SUCCEEDED\"}");
        insertOutboxRow(ev3, 402L, "payment.PaymentSucceeded", "{\"reservationId\":402,\"amountCents\":100,\"status\":\"SUCCEEDED\"}");

        runConcurrently(2, () -> outboxPublisher.poll());

        for (UUID eventId : List.of(ev1, ev2, ev3)) {
            assertPublishedAtLeastOnce(eventId);
            assertPublishedCount(eventId, 1);
        }
    }

    @Test
    void malformedEventIsDeadLetteredAndConsumerKeepsProcessing() throws Exception {
        long reservationId = 200L;
        UUID eventId = UUID.randomUUID();

        // 1. A record that cannot even be parsed is poison: neither processed nor silently dropped.
        kafka.send(new ProducerRecord<>(RESERVATION_EVENTS_TOPIC, "poison-key", "{not-json"))
                .get(10, TimeUnit.SECONDS);

        // 2. After retries are exhausted it lands on the dead-letter topic.
        assertThat(awaitOnTopic(RESERVATION_EVENTS_TOPIC + ".dlt", record ->
                "poison-key".equals(record.key()) && "{not-json".equals(record.value())))
                .as("poison record should be dead-lettered")
                .isTrue();

        // 3. The consumer is not wedged: a subsequent valid event is still processed.
        produceReservationCreated(eventId, reservationId, 20000, "corr-after-dlt");
        awaitPaymentStatus(reservationId, "SUCCEEDED");
        awaitProcessed(eventId);
    }

    @Test
    void malformedSupportedEventIsDeadLetteredAndConsumerKeepsProcessing() throws Exception {
        long reservationId = 201L;
        UUID poisonEventId = UUID.randomUUID();
        UUID validEventId = UUID.randomUUID();

        // Valid JSON, valid envelope, but invalid for a supported event: negative amountCents
        // must be refused deterministically and dead-lettered, not silently treated as a win.
        produceMalformedReservationCreated(poisonEventId, reservationId, -100, "corr-poison-201");

        assertThat(awaitOnTopic(RESERVATION_EVENTS_TOPIC + ".dlt",
                        record -> record.value().contains(poisonEventId.toString())))
                .as("negative amount should be dead-lettered")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.payments WHERE reservation_id = ?", Integer.class, reservationId))
                .as("no payment row for a poisoned event")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.processed_events WHERE event_id = ?", Integer.class, poisonEventId))
                .as("no processed row for a poisoned event (claim rolled back)")
                .isZero();

        // A valid event afterwards still processes.
        produceReservationCreated(validEventId, reservationId, 20000, "corr-after-poison");
        awaitPaymentStatus(reservationId, "SUCCEEDED");
        awaitProcessed(validEventId);
    }

    private boolean awaitOnTopic(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(topic)) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2)).records(topic)) {
                    if (match.test(record)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private void assertNotDeadLettered(UUID eventId) {
        // Retries run FixedBackOff(1000ms, 5) before the DLT, so wait longer than that
        // before declaring a duplicate was never classified as poison.
        long matching = countMatchingWithin(RESERVATION_EVENTS_TOPIC + ".dlt",
                record -> record.value().contains(eventId.toString()), Duration.ofSeconds(12));
        assertThat(matching)
                .as("idempotency duplicates must never be classified as poison")
                .isZero();
    }

    private long countMatchingWithin(String topic, Predicate<ConsumerRecord<String, String>> match, Duration window) {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(topic)) {
            Instant deadline = Instant.now().plus(window);
            long count = 0;
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1)).records(topic)) {
                    if (match.test(record)) {
                        count++;
                    }
                }
            }
            return count;
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

    private void produceReservationCreated(UUID eventId, long reservationId, int amountCents, String correlationId)
            throws Exception {
        String value = objectMapper.writeValueAsString(
                reservationCreatedEnvelope(eventId, reservationId, amountCents, correlationId));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC, new UUID(0L, reservationId).toString(), value);
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private void produceMalformedReservationCreated(UUID eventId, long reservationId, int amountCents, String correlationId)
            throws Exception {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId,
                "reservation.ReservationCreated",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of(
                        "reservationId", reservationId,
                        "customerId", 99L,
                        "eventId", 7L,
                        "seatIds", List.of(10L),
                        "amountCents", amountCents));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC,
                new UUID(0L, reservationId).toString(),
                objectMapper.writeValueAsString(envelope));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private EventEnvelope<JsonNode> reservationCreatedEnvelope(
            UUID eventId, long reservationId, int amountCents, String correlationId) {
        Map<String, Object> payload = Map.of(
                "reservationId", reservationId,
                "customerId", 99L,
                "eventId", 7L,
                "seatIds", List.of(10L),
                "amountCents", amountCents);
        return new EventEnvelope<>(
                eventId,
                "reservation.ReservationCreated",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                objectMapper.valueToTree(payload));
    }

    private void insertOutboxRow(UUID eventId, long reservationId, String eventType, String payload) {
        jdbc.update(
                "INSERT INTO payment.outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, correlation_id) "
                        + "VALUES (?, 'Payment', ?, ?, ?::jsonb, 'boot-test')",
                eventId, reservationId, eventType, payload);
    }

    private void awaitPaymentStatus(long reservationId, String status) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM payment.payments WHERE reservation_id = ? AND status = ?",
                    Integer.class, reservationId, status);
            if (rows != null && rows > 0) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("No " + status + " Payment created for reservation " + reservationId + " within 30s");
    }

    private void awaitProcessed(UUID eventId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM payment.processed_events WHERE event_id = ?", Integer.class, eventId);
            if (rows != null && rows > 0) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("Event " + eventId + " was never marked processed within 30s");
    }

    private void assertSinglePayment(long reservationId, String status, int amountCents) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.payments WHERE reservation_id = ?", Integer.class, reservationId);
        assertThat(rows).isEqualTo(1);
        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM payment.payments WHERE reservation_id = ?", String.class, reservationId);
        assertThat(paymentStatus).isEqualTo(status);
        Integer amount = jdbc.queryForObject(
                "SELECT amount_cents FROM payment.payments WHERE reservation_id = ?", Integer.class, reservationId);
        assertThat(amount).isEqualTo(amountCents);
    }

    private void assertProcessedOnce(UUID eventId) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.processed_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(rows).isEqualTo(1);
    }

    private void assertSameEventOutboxRows(long reservationId, int expected) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ?", Integer.class, reservationId);
        assertThat(rows).isEqualTo(expected);
    }

    private void assertPublishedAtLeastOnce(UUID eventId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM payment.outbox_events WHERE event_id = ? AND published_at IS NOT NULL",
                    Integer.class, eventId);
            if (rows != null && rows > 0) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("Outbox row " + eventId + " was never marked published within 30s");
    }

    private void assertPublishedCount(UUID eventId, int expected) throws Exception {
        long matching = countMatchingWithin(
                PAYMENT_EVENTS_TOPIC, record -> record.value().contains(eventId.toString()), Duration.ofSeconds(30));
        assertThat(matching).as("outbox row %s published count", eventId).isEqualTo(expected);
    }

    /**
     * Counts how many times the outcome for a reservation was observed in this test run.
     * With the outbox polled exactly once and no crash, that is exactly one — but this is
     * at-least-once delivery, not exactly-once (ADR 003): another poll (or a crash between
     * the Kafka send and the published_at commit) legitimately re-publishes the same
     * eventId, which consumers dedupe on (ADR 004).
     */
    private void assertOutcomePublishedOnceThisRun(long reservationId) throws Exception {
        String expectedKey = new UUID(0L, reservationId).toString();
        long count = countMatchingWithin(
                PAYMENT_EVENTS_TOPIC, record -> expectedKey.equals(record.key()), Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1);
    }

    private ConsumerRecord<String, String> awaitOutcome(long reservationId, String eventType) {
        String expectedKey = new UUID(0L, reservationId).toString();
        try (KafkaConsumer<String, String> consumer = kafkaConsumer(PAYMENT_EVENTS_TOPIC)) {
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(PAYMENT_EVENTS_TOPIC)) {
                    if (expectedKey.equals(record.key()) && record.value().contains("\"eventType\":\"" + eventType + "\"")) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No " + eventType + " event arrived for reservation " + reservationId);
    }

    private void runConcurrently(int threads, Runnable task) throws Exception {
        runConcurrently(java.util.stream.IntStream.range(0, threads).mapToObj(i -> task).toList());
    }

    private void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
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
        assertThat(failures)
                .as("no duplicate delivery may throw (idempotency is a no-op, not an error)")
                .isEmpty();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting async state", ex);
        }
    }
}
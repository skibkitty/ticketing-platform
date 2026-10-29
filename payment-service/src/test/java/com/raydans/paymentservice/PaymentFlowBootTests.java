package com.raydans.paymentservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.paymentservice.outbox.OutboxPublisher;
import com.raydans.paymentservice.payment.PaymentProcessingService;
import com.raydans.paymentservice.web.PaymentController;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentFlowBootTests {

    static final String RESERVATION_EVENTS_TOPIC = "reservation.events.v1";
    static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";
    static final long CUSTOMER_ID = 99L;
    static final long OTHER_CUSTOMER_ID = 77L;

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

    @Autowired
    TestRestTemplate rest;

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

    @Test
    void theOwnerReadsTheirPaymentOutcomeByReservationId() throws Exception {
        long reservationId = 500L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 46000, "corr-read-500");
        awaitPaymentStatus(reservationId, "FAILED");

        assertThat(jdbc.queryForObject(
                        "SELECT customer_id FROM payment.payments WHERE reservation_id = ?", Long.class, reservationId))
                .as("the owner is recorded from the ReservationCreated payload")
                .isEqualTo(CUSTOMER_ID);

        ResponseEntity<String> response = readPayment(reservationId, CUSTOMER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("reservationId").asLong()).isEqualTo(reservationId);
        assertThat(body.get("amountCents").asInt()).isEqualTo(46000);
        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("createdAt").isNull()).isFalse();
        assertThat(body.get("settledAt").isNull()).isFalse();
        assertThat(body.has("refundedAt")).as("refundedAt is always in the response").isTrue();
        assertThat(body.get("refundedAt").isNull()).as("and null for a Payment never refunded").isTrue();
    }

    @Test
    void aLatePaymentRejectedRefundsTheSucceededPaymentAndTheStatusRouteSaysSo() throws Exception {
        long reservationId = 600L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 44000, "corr-refund-600");
        awaitPaymentStatus(reservationId, "SUCCEEDED");

        UUID rejectedEventId = UUID.randomUUID();
        produceLatePaymentRejected(rejectedEventId, reservationId, "corr-refund-600");

        awaitPaymentStatus(reservationId, "REFUNDED");
        awaitProcessed(rejectedEventId);
        assertSinglePayment(reservationId, "REFUNDED", 44000);

        ResponseEntity<String> response = readPayment(reservationId, CUSTOMER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(body.get("settledAt").isNull()).isFalse();
        assertThat(body.get("refundedAt").isNull()).as("refundedAt is set on a refund").isFalse();
    }

    @Test
    void aRefundPublishesPaymentRefundedForTheCustomerToBeTold() throws Exception {
        long reservationId = 601L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 44000, "corr-refund-601");
        awaitPaymentStatus(reservationId, "SUCCEEDED");
        outboxPublisher.poll();
        awaitOutcome(reservationId, "payment.PaymentSucceeded");

        produceLatePaymentRejected(UUID.randomUUID(), reservationId, "corr-refund-601");
        awaitPaymentStatus(reservationId, "REFUNDED");
        outboxPublisher.poll();

        ConsumerRecord<String, String> refunded = awaitOutcome(reservationId, "payment.PaymentRefunded");
        assertThat(refunded.key()).isEqualTo(new UUID(0L, reservationId).toString());
        assertThat(new String(refunded.headers().lastHeader(CorrelationIdFilter.HEADER_NAME).value()))
                .isEqualTo("corr-refund-601");
        JsonNode payload = objectMapper.readTree(refunded.value()).get("payload");
        assertThat(payload.get("reservationId").asLong()).isEqualTo(reservationId);
        assertThat(payload.get("customerId").asLong()).isEqualTo(CUSTOMER_ID);
        assertThat(payload.get("amountCents").asInt()).isEqualTo(44000);
        assertThat(payload.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(payload.get("refundedAt").isNull()).isFalse();
    }

    @Test
    void concurrentLatePaymentRejectedEventsRefundOnceAndOnlyTheWinnerPublishes() throws Exception {
        long reservationId = 603L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 44000, "corr-refund-603");
        awaitPaymentStatus(reservationId, "SUCCEEDED");

        // Four distinct rejections (as if reservation-service had ignored four differently
        // ID'd successes) and two copies of one of them, all racing through the same bean:
        // the consumer is single-threaded, so the real race lives here, as in the
        // ReservationCreated race above.
        UUID duplicated = UUID.randomUUID();
        List<EventEnvelope<JsonNode>> envelopes = new ArrayList<>(List.of(
                latePaymentRejectedEnvelope(duplicated, reservationId),
                latePaymentRejectedEnvelope(duplicated, reservationId)));
        for (int i = 0; i < 4; i++) {
            envelopes.add(latePaymentRejectedEnvelope(UUID.randomUUID(), reservationId));
        }
        runConcurrently(envelopes.stream()
                .<Runnable>map(envelope -> () -> paymentProcessing.process(envelope, "corr-refund-603"))
                .toList());

        assertSinglePayment(reservationId, "REFUNDED", 44000);
        assertThat(refundedOutboxRows(reservationId))
                .as("only the transaction that won SUCCEEDED -> REFUNDED writes PaymentRefunded")
                .isEqualTo(1);
        assertProcessedOnce(duplicated);
        java.sql.Timestamp refundedAt = jdbc.queryForObject(
                "SELECT refunded_at FROM payment.payments WHERE reservation_id = ?", java.sql.Timestamp.class,
                reservationId);
        assertThat(refundedAt).isNotNull();

        // A consumer restart that replays the topic from an old offset delivers every one of
        // them again. The event claims and the status guard make all of it a no-op.
        for (EventEnvelope<JsonNode> envelope : envelopes) {
            paymentProcessing.process(envelope, "corr-refund-603-replay");
        }
        assertSinglePayment(reservationId, "REFUNDED", 44000);
        assertThat(refundedOutboxRows(reservationId)).as("a replay publishes nothing new").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT refunded_at FROM payment.payments WHERE reservation_id = ?", java.sql.Timestamp.class,
                        reservationId))
                .as("a replay does not move refundedAt")
                .isEqualTo(refundedAt);

        outboxPublisher.poll();
        assertThat(awaitOutcome(reservationId, "payment.PaymentRefunded")).isNotNull();
    }

    @Test
    void aLatePaymentRejectedForAPaymentThatNeverSucceededRefundsNothingAndIsNotDeadLettered() throws Exception {
        long reservationId = 604L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 46000, "corr-refund-604");
        awaitPaymentStatus(reservationId, "FAILED");

        UUID rejectedEventId = UUID.randomUUID();
        produceLatePaymentRejected(rejectedEventId, reservationId, "corr-refund-604");
        awaitProcessed(rejectedEventId);

        assertSinglePayment(reservationId, "FAILED", 46000);
        assertThat(refundedOutboxRows(reservationId)).isZero();
        assertNotDeadLettered(rejectedEventId);
    }

    @Test
    void aRedeliveredOrRepeatedLatePaymentRejectedRefundsOnceAndPublishesOnce() throws Exception {
        long reservationId = 602L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 44000, "corr-refund-602");
        awaitPaymentStatus(reservationId, "SUCCEEDED");

        UUID rejectedEventId = UUID.randomUUID();
        UUID repeatEventId = UUID.randomUUID();
        produceLatePaymentRejected(rejectedEventId, reservationId, "corr-refund-602");
        produceLatePaymentRejected(rejectedEventId, reservationId, "corr-refund-602");
        produceLatePaymentRejected(repeatEventId, reservationId, "corr-refund-602");

        awaitProcessed(repeatEventId);
        assertNotDeadLettered(rejectedEventId);
        assertNotDeadLettered(repeatEventId);
        assertSinglePayment(reservationId, "REFUNDED", 44000);
        assertProcessedOnce(rejectedEventId);
        Integer refundRows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ? AND event_type = 'payment.PaymentRefunded'",
                Integer.class, reservationId);
        assertThat(refundRows).as("one PaymentRefunded however often the rejection arrives").isEqualTo(1);
    }

    @Test
    void anotherCustomersPaymentIsAnsweredExactlyLikeOneThatDoesNotExist() throws Exception {
        long reservationId = 501L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 10000, "corr-read-501");
        awaitPaymentStatus(reservationId, "SUCCEEDED");

        ResponseEntity<String> notYours = readPayment(reservationId, CUSTOMER_ID + 1);
        ResponseEntity<String> unissued = readPayment(999_999L, CUSTOMER_ID);

        assertThat(notYours.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unissued.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The same body for both apart from the path the caller sent and the time, so
        // the response cannot be used to probe which reservation ids are paid for.
        assertThat(comparableErrorBody(notYours)).isEqualTo(comparableErrorBody(unissued));
        assertThat(objectMapper.readTree(notYours.getBody()).get("message").asText()).isEqualTo("Payment not found");
        assertThat(notYours.getBody()).doesNotContain("46000", "10000", "SUCCEEDED", "customer");
    }

    @Test
    void aLaterEventNamingAnotherCustomerDoesNotChangeWhoOwnsThePayment() throws Exception {
        long reservationId = 503L;
        produceReservationCreated(UUID.randomUUID(), reservationId, 10000, "corr-owner-503a", CUSTOMER_ID);
        awaitPaymentStatus(reservationId, "SUCCEEDED");

        UUID conflicting = UUID.randomUUID();
        produceReservationCreated(conflicting, reservationId, 10000, "corr-owner-503b", OTHER_CUSTOMER_ID);
        awaitProcessed(conflicting);

        assertSinglePayment(reservationId, "SUCCEEDED", 10000);
        assertThat(ownerOf(reservationId)).isEqualTo(CUSTOMER_ID);
        assertThat(readPayment(reservationId, CUSTOMER_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readPayment(reservationId, OTHER_CUSTOMER_ID).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentEventsNamingDifferentOwnersLeaveExactlyOneOwnerAndOnlyThatOneCanRead() throws Exception {
        long reservationId = 504L;
        EventEnvelope<JsonNode> mine =
                reservationCreatedEnvelope(UUID.randomUUID(), reservationId, 10000, "corr-owner-504a", CUSTOMER_ID);
        EventEnvelope<JsonNode> theirs =
                reservationCreatedEnvelope(UUID.randomUUID(), reservationId, 10000, "corr-owner-504b", OTHER_CUSTOMER_ID);

        runConcurrently(List.of(
                () -> paymentProcessing.process(mine, "corr-owner-504a"),
                () -> paymentProcessing.process(theirs, "corr-owner-504b"),
                () -> paymentProcessing.process(mine, "corr-owner-504a"),
                () -> paymentProcessing.process(theirs, "corr-owner-504b")));

        assertSinglePayment(reservationId, "SUCCEEDED", 10000);
        assertSameEventOutboxRows(reservationId, 1);
        // Either may win the race; what may not happen is both, neither, or a mixture.
        long owner = ownerOf(reservationId);
        assertThat(owner).isIn(CUSTOMER_ID, OTHER_CUSTOMER_ID);
        long loser = owner == CUSTOMER_ID ? OTHER_CUSTOMER_ID : CUSTOMER_ID;
        assertThat(readPayment(reservationId, owner).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readPayment(reservationId, loser).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonPositiveReservationIdsAreClientErrorsNotLookups() {
        assertThat(readPayment(0L, CUSTOMER_ID).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readPayment(-1L, CUSTOMER_ID).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private long ownerOf(long reservationId) {
        return jdbc.queryForObject(
                "SELECT customer_id FROM payment.payments WHERE reservation_id = ?", Long.class, reservationId);
    }

    /** An error body without the two fields that legitimately differ per request. */
    private JsonNode comparableErrorBody(ResponseEntity<String> response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBody());
        body.remove("timestamp");
        body.remove("path");
        return body;
    }

    @Test
    void aPaymentRecordedBeforeOwnershipWasStoredIsReadableByNobody() throws Exception {
        long reservationId = 502L;
        // A pre-V4 row: no customer_id, and no way to recover one without querying
        // another service's schema (ADR 005). The read fails closed on it (ADR 016).
        jdbc.update(
                "INSERT INTO payment.payments (reservation_id, amount_cents, status, settled_at) "
                        + "VALUES (?, 1000, 'SUCCEEDED', now())",
                reservationId);

        assertThat(readPayment(reservationId, CUSTOMER_ID).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> readPayment(long reservationId, long customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PaymentController.CUSTOMER_HEADER, Long.toString(customerId));
        return rest.exchange(
                "/api/v1/payments/{reservationId}", HttpMethod.GET, new HttpEntity<>(headers), String.class, reservationId);
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
        produceReservationCreated(eventId, reservationId, amountCents, correlationId, CUSTOMER_ID);
    }

    private void produceReservationCreated(
            UUID eventId, long reservationId, int amountCents, String correlationId, long customerId) throws Exception {
        String value = objectMapper.writeValueAsString(
                reservationCreatedEnvelope(eventId, reservationId, amountCents, correlationId, customerId));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC, new UUID(0L, reservationId).toString(), value);
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    /**
     * What reservation-service publishes when its ADR 007 guard ignores a late
     * {@code PaymentSucceeded}: the fact, on its own topic, that the money came too late.
     */
    private void produceLatePaymentRejected(UUID eventId, long reservationId, String correlationId) throws Exception {
        long paymentId = jdbc.queryForObject(
                "SELECT id FROM payment.payments WHERE reservation_id = ?", Long.class, reservationId);
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                eventId,
                "reservation.LatePaymentRejected",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                objectMapper.valueToTree(Map.of("reservationId", reservationId, "paymentId", paymentId)));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC, new UUID(0L, reservationId).toString(), objectMapper.writeValueAsString(envelope));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private EventEnvelope<JsonNode> latePaymentRejectedEnvelope(UUID eventId, long reservationId) {
        long paymentId = jdbc.queryForObject(
                "SELECT id FROM payment.payments WHERE reservation_id = ?", Long.class, reservationId);
        return new EventEnvelope<>(
                eventId,
                "reservation.LatePaymentRejected",
                Instant.now(),
                "corr-refund-" + reservationId,
                new UUID(0L, reservationId),
                objectMapper.valueToTree(Map.of("reservationId", reservationId, "paymentId", paymentId)));
    }

    private int refundedOutboxRows(long reservationId) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ? AND event_type = 'payment.PaymentRefunded'",
                Integer.class, reservationId);
        return rows == null ? 0 : rows;
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
                        "customerId", CUSTOMER_ID,
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
        return reservationCreatedEnvelope(eventId, reservationId, amountCents, correlationId, CUSTOMER_ID);
    }

    private EventEnvelope<JsonNode> reservationCreatedEnvelope(
            UUID eventId, long reservationId, int amountCents, String correlationId, long customerId) {
        Map<String, Object> payload = Map.of(
                "reservationId", reservationId,
                "customerId", customerId,
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

    @Test
    void aPaymentsHopsAreLoggedAsJsonUnderTheReservationsCorrelationId(CapturedOutput output) throws Exception {
        // #14: the id the reservation was placed under, carried across Kafka, on
        // the line that decides the payment and the one that publishes its outcome.
        long reservationId = 120L;
        UUID eventId = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        produceReservationCreated(eventId, reservationId, 12000, correlationId);

        awaitPaymentStatus(reservationId, "SUCCEEDED");
        outboxPublisher.poll();

        List<String> messages = jsonLinesCarrying(output, correlationId).stream()
                .map(line -> line.path("message").asText())
                .toList();
        assertThat(messages)
                .anySatisfy(message -> assertThat(message)
                        .contains("Payment for reservation " + reservationId + " SUCCEEDED"))
                .anySatisfy(message -> assertThat(message).contains("Published payment.PaymentSucceeded"));
    }

    /**
     * The captured console lines that are JSON objects whose top-level
     * {@code correlationId} is {@code correlationId}: what an operator grepping
     * for the id would find, read the way a log pipeline would read it.
     */
    private static List<JsonNode> jsonLinesCarrying(CapturedOutput output, String correlationId) {
        ObjectMapper json = new ObjectMapper();
        return output.getOut().lines()
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (JsonProcessingException notJson) {
                        return null;
                    }
                })
                .filter(line -> line != null && correlationId.equals(line.path(CorrelationIdFilter.MDC_KEY).asText()))
                .toList();
    }
}

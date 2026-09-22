package com.raydans.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.paymentservice.outbox.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    void duplicateDeliveryProducesSinglePaymentAndSingleOutcomeEvent() throws Exception {
        long reservationId = 103L;
        UUID eventId = UUID.randomUUID();
        produceReservationCreated(eventId, reservationId, 44000, "corr-dupe-103");
        produceReservationCreated(eventId, reservationId, 44000, "corr-dupe-103");

        awaitPaymentStatus(reservationId, "SUCCEEDED");
        awaitProcessed(eventId);
        outboxPublisher.poll();

        assertSinglePayment(reservationId, "SUCCEEDED", 44000);
        assertProcessedOnce(eventId);
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ? AND event_type = 'payment.PaymentSucceeded'",
                Integer.class, reservationId);
        assertThat(outboxRows).isEqualTo(1);
        assertOutcomePublishedExactlyOnce(reservationId);
    }

    private void produceReservationCreated(UUID eventId, long reservationId, int amountCents, String correlationId)
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
        String value = objectMapper.writeValueAsString(envelope);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC, new UUID(0L, reservationId).toString(), value);
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
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
        throw new AssertionError(
                "No " + status + " Payment created for reservation " + reservationId + " within 30s");
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

    private void assertOutcomePublishedExactlyOnce(long reservationId) throws Exception {
        String expectedKey = new UUID(0L, reservationId).toString();
        int count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(PAYMENT_EVENTS_TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(PAYMENT_EVENTS_TOPIC)) {
                    if (expectedKey.equals(record.key())) {
                        count++;
                    }
                }
            }
        }
        assertThat(count).isEqualTo(1);
    }

    private ConsumerRecord<String, String> awaitOutcome(long reservationId, String eventType) {
        String expectedKey = new UUID(0L, reservationId).toString();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(PAYMENT_EVENTS_TOPIC));
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting async state", ex);
        }
    }
}

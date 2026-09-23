package com.raydans.paymentservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.paymentservice.payment.PaymentProcessingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationCreatedConsumerTest {

    @Mock
    PaymentProcessingService processing;

    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    ReservationCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReservationCreatedConsumer(processing, objectMapper);
    }

    @Test
    void kafkaCorrelationHeaderWinsOverTheEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "reservation.ReservationCreated", 101L, 45000));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, "header-cid".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertCorrelationId("header-cid");
    }

    @Test
    void envelopeCorrelationIdIsUsedWhenHeaderIsAbsent() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "reservation.ReservationCreated", 102L, 45000));

        consumer.onMessage(record);

        assertCorrelationId("envelope-cid");
    }

    @Test
    void blankKafkaHeaderFallsBackToEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "reservation.ReservationCreated", 103L, 45000));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, " ".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertCorrelationId("envelope-cid");
    }

    @Test
    void bothAbsentMintsARandomUuidSoMdcAlwaysHasATraceId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope(null, "reservation.ReservationCreated", 104L, 45000));

        consumer.onMessage(record);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(processing).process(any(EventEnvelope.class), captor.capture());
        assertThat(captor.getValue()).isNotBlank();
        assertThat(UUID.fromString(captor.getValue())).isNotNull();
    }

    @Test
    void malformedJsonThrowsSoTheRecordCanBeRetriedAndDeadLettered() {
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(
                        record("{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialize"));
        verify(processing, never()).process(any(), any());
    }

    private void assertCorrelationId(String expected) {
        ArgumentCaptor<EventEnvelope<JsonNode>> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(processing).process(envelopeCaptor.capture(), correlationCaptor.capture());
        assertThat(correlationCaptor.getValue()).isEqualTo(expected);
    }

    private String envelope(String correlationId, String eventType, long reservationId, int amountCents)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of(
                        "reservationId", reservationId,
                        "customerId", 99L,
                        "eventId", 7L,
                        "seatIds", java.util.List.of(10L),
                        "amountCents", amountCents));
        return objectMapper.writeValueAsString(envelope);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("reservation.events.v1", 0, 0L, "key", value);
    }
}
package com.raydans.reservationservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.reservation.ReservationConfirmationService;
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
class PaymentOutcomeConsumerTest {

    @Mock
    ReservationConfirmationService confirmation;

    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    PaymentOutcomeConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentOutcomeConsumer(confirmation, objectMapper);
    }

    @Test
    void kafkaCorrelationHeaderWinsOverTheEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "payment.PaymentSucceeded", 101L));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, "header-cid".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertCorrelationId("header-cid");
    }

    @Test
    void envelopeCorrelationIdIsUsedWhenHeaderIsAbsent() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "payment.PaymentSucceeded", 102L));

        consumer.onMessage(record);

        assertCorrelationId("envelope-cid");
    }

    @Test
    void blankKafkaHeaderFallsBackToEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope("envelope-cid", "payment.PaymentSucceeded", 103L));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, " ".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertCorrelationId("envelope-cid");
    }

    @Test
    void bothAbsentMintsARandomUuidSoMdcAlwaysHasATraceId() throws Exception {
        ConsumerRecord<String, String> record =
                record(envelope(null, "payment.PaymentSucceeded", 104L));

        consumer.onMessage(record);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(confirmation).process(any(EventEnvelope.class), captor.capture());
        assertThat(captor.getValue()).isNotBlank();
        assertThat(UUID.fromString(captor.getValue())).isNotNull();
    }

    @Test
    void malformedJsonThrowsSoTheRecordCanBeRetriedAndDeadLettered() {
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(
                        record("{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialize"));
        verify(confirmation, never()).process(any(), any());
    }

    private void assertCorrelationId(String expected) {
        ArgumentCaptor<EventEnvelope<JsonNode>> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(confirmation).process(envelopeCaptor.capture(), correlationCaptor.capture());
        assertThat(correlationCaptor.getValue()).isEqualTo(expected);
    }

    private String envelope(String correlationId, String eventType, long reservationId)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of("paymentId", 1L, "reservationId", reservationId, "amountCents", 27000, "status", "SUCCEEDED"));
        return objectMapper.writeValueAsString(envelope);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("payment.events.v1", 0, 0L, "key", value);
    }
}
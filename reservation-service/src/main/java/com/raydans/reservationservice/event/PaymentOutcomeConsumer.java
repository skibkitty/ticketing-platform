package com.raydans.reservationservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.reservation.ReservationConfirmationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka seam for the money-step outcome: subscribes to {@code payment.events.v1} and
 * hands each {@code PaymentSucceeded} to the idempotent confirmation service (ADR 004/007).
 */
@Component
public class PaymentOutcomeConsumer {

    private final ReservationConfirmationService confirmation;
    private final ObjectMapper objectMapper;

    public PaymentOutcomeConsumer(
            ReservationConfirmationService confirmation, ObjectMapper objectMapper) {
        this.confirmation = confirmation;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topics.payment-outcome}")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(record.value(), new TypeReference<EventEnvelope<JsonNode>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize event envelope", ex);
        }
        String correlationId = resolveCorrelationId(record, envelope.correlationId());
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            confirmation.process(envelope, correlationId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /**
     * Correlation ids are optional at the platform edge, not mandatory (the web
     * {@code CorrelationIdFilter} and reservation-service's producer both mint a UUID when
     * one is absent), so a missing/blank id here does not reject the message — it is minted
     * so MDC always carries a non-blank trace id. Precedence: Kafka header, then the
     * envelope's own id, then a freshly generated UUID.
     */
    private String resolveCorrelationId(ConsumerRecord<String, String> record, String envelopeCorrelationId) {
        Header header = record.headers().lastHeader(CorrelationIdFilter.HEADER_NAME);
        String fromHeader = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        return envelopeCorrelationId == null || envelopeCorrelationId.isBlank()
                ? UUID.randomUUID().toString()
                : envelopeCorrelationId;
    }
}
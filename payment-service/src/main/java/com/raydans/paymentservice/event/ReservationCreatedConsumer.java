package com.raydans.paymentservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.paymentservice.payment.PaymentProcessingService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The platform's first inbound Kafka seam: subscribes to {@code reservation.events.v1} and hands
 * each {@code ReservationCreated} to the idempotent processing service.
 */
@Component
public class ReservationCreatedConsumer {

    private final PaymentProcessingService processing;
    private final ObjectMapper objectMapper;

    public ReservationCreatedConsumer(PaymentProcessingService processing, ObjectMapper objectMapper) {
        this.processing = processing;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topics.reservation-created}")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope<JsonNode> envelope = objectMapper.readValue(
                    record.value(), new TypeReference<EventEnvelope<JsonNode>>() {});
            String correlationId = headerOrElse(record, envelope.correlationId());
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            try {
                processing.process(envelope, correlationId);
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize event envelope", ex);
        }
    }

    private String headerOrElse(ConsumerRecord<String, String> record, String fallback) {
        Header header = record.headers().lastHeader(CorrelationIdFilter.HEADER_NAME);
        if (header == null) {
            // A producer normally stamps the header; fall back to the envelope's own trace id,
            // then only invent one as a last resort so MDC always has a value to log.
            return fallback == null || fallback.isBlank() ? UUID.randomUUID().toString() : fallback;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}

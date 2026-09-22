package com.raydans.reservationservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {

    public static final String RESERVATION_EVENTS_TOPIC = "reservation.events.v1";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void poll() {
        List<OutboxEventEntity> unpublished = outbox.findUnpublishedBatch();
        for (OutboxEventEntity row : unpublished) {
            publish(row);
        }
    }

    void publish(OutboxEventEntity row) {
        try {
            String key = aggregateId(row.getAggregateId()).toString();
            EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                    row.getEventId(),
                    row.getEventType(),
                    Instant.now(),
                    row.getCorrelationId(),
                    aggregateId(row.getAggregateId()),
                    objectMapper.readTree(row.getPayload()));
            String value = objectMapper.writeValueAsString(envelope);

            Message<String> message = MessageBuilder.withPayload(value)
                    .setHeader(KafkaHeaders.TOPIC, RESERVATION_EVENTS_TOPIC)
                    .setHeader(KafkaHeaders.KEY, key)
                    .setHeader(CorrelationIdFilter.HEADER_NAME, row.getCorrelationId())
                    .build();
            kafka.send(message).get(10, TimeUnit.SECONDS);
            row.markPublished();
            outbox.save(row);
        } catch (Exception ex) {
            log.warn("Failed to publish outbox event {} for aggregate {}; it stays unpublished and will be retried",
                    row.getId(), row.getAggregateId(), ex);
        }
    }

    private UUID aggregateId(long id) {
        return new UUID(0L, id);
    }
}
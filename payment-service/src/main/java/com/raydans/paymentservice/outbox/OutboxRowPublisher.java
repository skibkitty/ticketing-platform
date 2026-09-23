package com.raydans.paymentservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a single outbox row and marks it published, in one transaction that is new per
 * row (REQUIRES_NEW). Because this is a separate bean, {@link OutboxPublisher#poll()} goes
 * through the proxy and each row gets its own transaction instead of all rows sharing (and
 * holding locks for) one long transaction.
 *
 * <p>The transactional claim is {@code SELECT ... FOR UPDATE WHERE published_at IS NULL}:
 * two concurrent publishers cannot both own the same row, a failed send leaves the row
 * unpublished (the rollback/commit releases the lock and {@code published_at} stays NULL so
 * the next poll retries it), and a successful send commits {@code published_at}. The Kafka
 * send happens while the row's lock is held, but only for this one row and only for the
 * duration of one send — not a whole batch.
 */
@Component
public class OutboxRowPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxRowPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public OutboxRowPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes {@code candidate} and marks it published.
     *
     * <p>At-least-once (ADR 003): if the broker accepts the message but this process crashes
     * (or the transaction fails) before the commit that sets {@code published_at}, the row is
     * claimed again on the next poll and the same {@code eventId} is sent again. Consumers
     * must be idempotent on that {@code eventId} (ADR 004).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxEventEntity candidate) {
        OutboxEventEntity row = outbox.findAndLockPending(candidate.getId()).orElse(null);
        if (row == null) {
            log.debug("Skipping outbox row {} already published or claimed elsewhere", candidate.getId());
            return;
        }
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
                    .setHeader(KafkaHeaders.TOPIC, OutboxPublisher.PAYMENT_EVENTS_TOPIC)
                    .setHeader(KafkaHeaders.KEY, key)
                    .setHeader(CorrelationIdFilter.HEADER_NAME, row.getCorrelationId())
                    .build();
            kafka.send(message).get(10, TimeUnit.SECONDS);
            row.markPublished();
            outbox.save(row);
        } catch (Exception ex) {
            // The send (or the re-serialize) failed before published_at was written; the
            // transaction commits with no changes, the row lock is released, and the next
            // poll re-claims and re-publishes this row.
            log.warn("Failed to publish outbox event {} for aggregate {}; it stays unpublished and will be retried",
                    row.getId(), row.getAggregateId(), ex);
        }
    }

    private UUID aggregateId(long id) {
        return new UUID(0L, id);
    }
}
package com.raydans.paymentservice.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    public static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final OutboxRowPublisher rowPublisher;

    public OutboxPublisher(OutboxEventRepository outbox, OutboxRowPublisher rowPublisher) {
        this.outbox = outbox;
        this.rowPublisher = rowPublisher;
    }

    /**
     * Reads a candidate batch without holding any row locks (the {@code SKIP LOCKED} read
     * just excludes rows another publisher currently has locked) and hands each row to
     * {@link OutboxRowPublisher}, which re-claims it with a row lock in its own
     * REQUIRES_NEW transaction (ADR 003). A Kafka send therefore holds only the lock for its
     * own row for the duration of one send, not a lock on the whole batch for the duration
     * of all sends.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void poll() {
        List<OutboxEventEntity> unpublished = outbox.findUnpublishedBatch();
        for (OutboxEventEntity row : unpublished) {
            rowPublisher.publish(row);
        }
    }
}
package com.raydans.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    OutboxEventRepository outbox;

    @Mock
    KafkaTemplate<String, String> kafka;

    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    OutboxRowPublisher rowPublisher;
    OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        rowPublisher = new OutboxRowPublisher(outbox, kafka, objectMapper);
        publisher = new OutboxPublisher(outbox, rowPublisher);
    }

    @Test
    void publishBuildsKeyedEnvelopeCarryingCorrelationHeaderAndMarksPublishedOnAck() {
        OutboxEventEntity row = row(1L, "cid-123");

        when(outbox.findAndLockPending(1L)).thenReturn(Optional.of(row));
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> completed());

        rowPublisher.publish(row);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafka).send(captor.capture());
        Message<String> message = captor.getValue();

        assertThat(message.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.TOPIC))
                .isEqualTo(OutboxPublisher.PAYMENT_EVENTS_TOPIC);
        assertThat(message.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.KEY))
                .isEqualTo(new UUID(0L, 42L).toString());
        assertThat(message.getHeaders().get("X-Correlation-Id")).isEqualTo("cid-123");
        assertThat(message.getPayload())
                .contains("\"eventType\":\"payment.PaymentSucceeded\"")
                .contains("\"correlationId\":\"cid-123\"")
                .contains("\"aggregateId\":\"" + new UUID(0L, 42L) + "\"")
                .contains("\"reservationId\":42")
                .contains("\"amountCents\":27000")
                .contains("\"status\":\"SUCCEEDED\"");

        assertThat(row.getPublishedAt()).isNotNull();
        verify(outbox).save(row);
    }

    @Test
    void failedSendLeavesRowUnpublishedForRetry() {
        OutboxEventEntity row = row(2L, "cid-123");

        when(outbox.findAndLockPending(2L)).thenReturn(Optional.of(row));
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("broker down"));
            return f;
        });

        rowPublisher.publish(row);

        assertThat(row.getPublishedAt()).isNull();
        verify(outbox, never()).save(row);
    }

    @Test
    void rowAlreadyClaimedAndPublishedElsewhereIsSkipped() {
        OutboxEventEntity row = row(3L, "cid-123");

        when(outbox.findAndLockPending(3L)).thenReturn(Optional.empty());

        rowPublisher.publish(row);

        verify(kafka, never()).send(any(Message.class));
        verify(outbox, never()).save(row);
    }

    @Test
    void pollPublishesEachUnpublishedRow() {
        OutboxEventEntity row = row(4L, "cid-7");

        when(outbox.findUnpublishedBatch()).thenReturn(List.of(row));
        when(outbox.findAndLockPending(4L)).thenReturn(Optional.of(row));
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> completed());

        publisher.poll();

        verify(kafka, org.mockito.Mockito.times(1)).send(any(Message.class));
        verify(outbox).save(row);
        assertThat(row.getPublishedAt()).isNotNull();
    }

    @Test
    void pollContinuesAfterOneSendFailureLeavingOnlyThatRowUnpublished() {
        OutboxEventEntity failed = row(5L, "cid-41");
        OutboxEventEntity ok = row(6L, "cid-42");

        when(outbox.findUnpublishedBatch()).thenReturn(List.of(failed, ok));
        when(outbox.findAndLockPending(5L)).thenReturn(Optional.of(failed));
        when(outbox.findAndLockPending(6L)).thenReturn(Optional.of(ok));
        when(kafka.send(any(Message.class)))
                .thenAnswer(invocation -> {
                    CompletableFuture<Object> f = new CompletableFuture<>();
                    f.completeExceptionally(new RuntimeException("broker down"));
                    return f;
                })
                .thenAnswer(invocation -> completed());

        publisher.poll();

        assertThat(failed.getPublishedAt()).isNull();
        assertThat(ok.getPublishedAt()).isNotNull();
        verify(outbox, never()).save(failed);
        verify(outbox).save(ok);
    }

    @Test
    void pollWhenNothingPendingSendsNothing() {
        when(outbox.findUnpublishedBatch()).thenReturn(List.of());

        publisher.poll();

        verify(kafka, org.mockito.Mockito.never()).send(any(Message.class));
    }

    @Test
    void aRowLockedByAConcurrentPublisherIsSkippedByTheBatchThenClaimedByItsOwner() {
        // Simulates the at-least-once window documented in ADR 003: a row whose publisher
        // crashed between the Kafka send and the commit that sets published_at is still
        // unpublished, so a later poll claims it again and re-sends the SAME eventId. The
        // later consumer dedupe (processed_events) is what makes the duplicate harmless.
        OutboxEventEntity row = row(7L, "cid-9");

        when(outbox.findUnpublishedBatch()).thenReturn(List.of(row));
        when(outbox.findAndLockPending(7L)).thenReturn(Optional.of(row));
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> completed());

        publisher.poll();

        assertThat(row.getPublishedAt()).isNotNull();
        verify(kafka).send(any(Message.class));
    }

    private OutboxEventEntity row(long id, String correlationId) {
        OutboxEventEntity row = new OutboxEventEntity(
                UUID.randomUUID(),
                "Payment",
                42L,
                "payment.PaymentSucceeded",
                """
                        {"paymentId":9,"reservationId":42,"amountCents":27000,"status":"SUCCEEDED"}""",
                correlationId);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private CompletableFuture<Object> completed() {
        CompletableFuture<Object> f = new CompletableFuture<>();
        f.complete(null);
        return f;
    }
}
package com.raydans.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    OutboxEventRepository outbox;

    @Mock
    KafkaTemplate<String, String> kafka;

    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publishBuildsKeyedEnvelopeCarryingCorrelationHeaderAndMarksPublishedOnAck() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity row = new OutboxEventEntity(
                eventId,
                "Payment",
                42L,
                "payment.PaymentSucceeded",
                """
                        {"paymentId":9,"reservationId":42,"amountCents":27000,"status":"SUCCEEDED"}""",
                "cid-123");
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> completed());
        OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, objectMapper);

        publisher.publish(row);

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
                .contains("\"eventId\":\"" + eventId + "\"")
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
        OutboxEventEntity row = new OutboxEventEntity(
                UUID.randomUUID(), "Payment", 42L, "payment.PaymentSucceeded",
                "{\"reservationId\":42}", "cid-123");
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("broker down"));
            return f;
        });
        OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, objectMapper);

        publisher.publish(row);

        assertThat(row.getPublishedAt()).isNull();
        verify(outbox, never()).save(row);
    }

    @Test
    void pollPublishesEachUnpublishedRow() {
        OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, objectMapper);
        OutboxEventEntity row = new OutboxEventEntity(
                UUID.randomUUID(), "Payment", 7L, "payment.PaymentFailed",
                "{\"reservationId\":7}", "cid-7");
        when(outbox.findUnpublishedBatch()).thenReturn(List.of(row));
        when(kafka.send(any(Message.class))).thenAnswer(invocation -> completed());

        publisher.poll();

        verify(kafka, org.mockito.Mockito.times(1)).send(any(Message.class));
        verify(outbox).save(row);
    }

    @Test
    void pollContinuesAfterOneSendFailureLeavingOnlyThatRowUnpublished() {
        OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, objectMapper);
        OutboxEventEntity failed = new OutboxEventEntity(
                UUID.randomUUID(), "Payment", 41L, "payment.PaymentFailed",
                "{\"reservationId\":41}", "cid-41");
        OutboxEventEntity ok = new OutboxEventEntity(
                UUID.randomUUID(), "Payment", 42L, "payment.PaymentSucceeded",
                "{\"reservationId\":42}", "cid-42");
        when(outbox.findUnpublishedBatch()).thenReturn(List.of(failed, ok));
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
        OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, objectMapper);
        when(outbox.findUnpublishedBatch()).thenReturn(List.of());

        publisher.poll();

        verify(kafka, org.mockito.Mockito.never()).send(any(Message.class));
    }

    private CompletableFuture<Object> completed() {
        CompletableFuture<Object> f = new CompletableFuture<>();
        f.complete(null);
        return f;
    }
}

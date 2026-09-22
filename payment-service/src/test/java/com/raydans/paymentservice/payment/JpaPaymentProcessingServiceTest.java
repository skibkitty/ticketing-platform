package com.raydans.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.paymentservice.outbox.OutboxEventEntity;
import com.raydans.paymentservice.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JpaPaymentProcessingServiceTest {

    static final String CORRELATION_ID = "test-correlation-id";

    @Mock
    PaymentRepository payments;

    @Mock
    ProcessedEventRepository processedEvents;

    @Mock
    OutboxEventRepository outbox;

    final ObjectMapper objectMapper = new ObjectMapper();

    JpaPaymentProcessingService service;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.setDeclineThresholdCents(50000);
        service = new JpaPaymentProcessingService(payments, processedEvents, outbox, properties, objectMapper);
    }

    @Test
    void processBelowThresholdCreatesSucceededPaymentOutboxRowAndProcessedRow() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);
        when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 42L);
            return created;
        });

        service.process(envelope(eventId, "reservation.ReservationCreated", 101L, 45000), CORRELATION_ID);

        ArgumentCaptor<PaymentEntity> paymentCaptor = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(payments).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getReservationId()).isEqualTo(101L);
        assertThat(paymentCaptor.getValue().getAmountCents()).isEqualTo(45000);
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentCaptor.getValue().getSettledAt()).isNotNull();

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEventEntity outboxRow = outboxCaptor.getValue();
        assertThat(outboxRow.getEventType()).isEqualTo("payment.PaymentSucceeded");
        assertThat(outboxRow.getAggregateType()).isEqualTo("Payment");
        assertThat(outboxRow.getAggregateId()).isEqualTo(101L);
        assertThat(outboxRow.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxRow.getPayload())
                .contains("\"paymentId\":42")
                .contains("\"reservationId\":101")
                .contains("\"amountCents\":45000")
                .contains("\"status\":\"SUCCEEDED\"");

        assertProcessed(eventId);
    }

    @Test
    void processAtOrAboveThresholdCreatesFailedPaymentAndPublishesPaymentFailed() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);
        when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 43L);
            return created;
        });

        service.process(envelope(eventId, "reservation.ReservationCreated", 102L, 50000), CORRELATION_ID);

        ArgumentCaptor<PaymentEntity> paymentCaptor = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(payments).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("payment.PaymentFailed");
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"status\":\"FAILED\"");
        assertProcessed(eventId);
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(true);

        service.process(envelope(eventId, "reservation.ReservationCreated", 103L, 45000), CORRELATION_ID);

        verify(payments, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    void nonReservationCreatedEventTypeIsIgnored() {
        UUID eventId = UUID.randomUUID();

        service.process(envelope(eventId, "reservation.ReservationConfirmed", 104L, 45000), CORRELATION_ID);

        verify(processedEvents, never()).existsById(any());
        verify(payments, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    void eventIdUniquenessAndReservationIdAreCarriedThrough() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);
        when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 44L);
            return created;
        });

        service.process(envelope(eventId, "reservation.ReservationCreated", 1L, 45000), CORRELATION_ID);

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(1L);
        assertProcessed(eventId);
    }

    private void assertProcessed(UUID eventId) {
        ArgumentCaptor<ProcessedEventEntity> processedCaptor = ArgumentCaptor.forClass(ProcessedEventEntity.class);
        verify(processedEvents).save(processedCaptor.capture());
        assertThat(processedCaptor.getValue().getEventId()).isEqualTo(eventId);
    }

    private EventEnvelope<JsonNode> envelope(UUID eventId, String eventType, long reservationId, int amountCents) {
        JsonNode payload = objectMapper.valueToTree(
                new JpaPaymentProcessingService.ReservationCreatedPayload(
                        reservationId, 99L, 7L, List.of(10L), amountCents));
        return new EventEnvelope<>(
                eventId, eventType, Instant.now(), CORRELATION_ID, new UUID(0L, reservationId), payload);
    }
}

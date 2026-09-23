package com.raydans.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.paymentservice.outbox.OutboxEventEntity;
import com.raydans.paymentservice.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    ProcessedEventRepository processedEvents;

    @Mock
    PaymentRepository payments;

    @Mock
    OutboxEventRepository outbox;

    final ObjectMapper objectMapper = new ObjectMapper();

    JpaPaymentProcessingService service;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.setDeclineThresholdCents(50000);
        service = new JpaPaymentProcessingService(processedEvents, payments, outbox, properties, objectMapper);
    }

    @Test
    void processBelowThresholdCreatesSucceededPaymentOutboxRowAndProcessedRow() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(payments.tryClaim(eq(101L), eq(45000), eq("SUCCEEDED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(101L)).thenReturn(Optional.of(settledPayment(42L, 101L, 45000, PaymentStatus.SUCCEEDED)));

        service.process(envelope(eventId, "reservation.ReservationCreated", 101L, 45000), CORRELATION_ID);

        verify(processedEvents).tryClaim(eventId);
        verify(payments).tryClaim(eq(101L), eq(45000), eq("SUCCEEDED"), any(Instant.class));

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
    }

    @Test
    void processExactlyAtThresholdCreatesFailedPaymentAndPublishesPaymentFailed() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(payments.tryClaim(eq(102L), eq(50000), eq("FAILED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(102L)).thenReturn(Optional.of(settledPayment(43L, 102L, 50000, PaymentStatus.FAILED)));

        service.process(envelope(eventId, "reservation.ReservationCreated", 102L, 50000), CORRELATION_ID);

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("payment.PaymentFailed");
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"status\":\"FAILED\"");
    }

    @Test
    void processOneCentBelowThresholdSucceeds() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(payments.tryClaim(eq(103L), eq(49999), eq("SUCCEEDED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(103L)).thenReturn(Optional.of(settledPayment(44L, 103L, 49999, PaymentStatus.SUCCEEDED)));

        service.process(envelope(eventId, "reservation.ReservationCreated", 103L, 49999), CORRELATION_ID);

        verify(outbox).save(any(OutboxEventEntity.class));
    }

    @Test
    void zeroAmountIsValidAndSucceeds() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(payments.tryClaim(eq(104L), eq(0), eq("SUCCEEDED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(104L)).thenReturn(Optional.of(settledPayment(45L, 104L, 0, PaymentStatus.SUCCEEDED)));

        service.process(envelope(eventId, "reservation.ReservationCreated", 104L, 0), CORRELATION_ID);

        verify(outbox).save(any(OutboxEventEntity.class));
    }

    @Test
    void sequentialDuplicateDeliveryIsANoOp() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(0);

        service.process(envelope(eventId, "reservation.ReservationCreated", 105L, 45000), CORRELATION_ID);

        verify(payments, never()).tryClaim(anyLong(), anyInt(), anyString(), any(Instant.class));
        verify(payments, never()).findByReservationId(anyLong());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void duplicateAfterSuccessfulProcessingIsANoOp() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(0);

        service.process(envelope(eventId, "reservation.ReservationCreated", 106L, 45000), CORRELATION_ID);

        verify(payments, never()).tryClaim(anyLong(), anyInt(), anyString(), any(Instant.class));
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void failedProcessingRollsBackTheClaimSoARetriedDeliveryReprocesses() {
        UUID eventId = UUID.randomUUID();
        // First delivery: the claim succeeds but the outbox write explodes, so the
        // transaction (and with it the processed_events claim) must roll back.
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(payments.tryClaim(eq(107L), eq(45000), eq("SUCCEEDED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(107L))
                .thenReturn(Optional.of(settledPayment(46L, 107L, 45000, PaymentStatus.SUCCEEDED)));
        when(outbox.save(any(OutboxEventEntity.class)))
                .thenThrow(new IllegalStateException("outbox exploded"));

        assertThatThrownBy(() -> service.process(
                        envelope(eventId, "reservation.ReservationCreated", 107L, 45000), CORRELATION_ID))
                .isInstanceOf(IllegalStateException.class);

        // Second (redelivered) delivery: claims again and completes.
        when(outbox.save(any(OutboxEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.process(envelope(eventId, "reservation.ReservationCreated", 107L, 45000), CORRELATION_ID);

        verify(processedEvents, times(2)).tryClaim(eventId);
        verify(outbox, times(2)).save(any(OutboxEventEntity.class));
    }

    @Test
    void nonReservationCreatedEventTypeIsIgnored() {
        UUID eventId = UUID.randomUUID();

        service.process(envelope(eventId, "reservation.ReservationConfirmed", 108L, 45000), CORRELATION_ID);

        verify(processedEvents, never()).tryClaim(any());
        verify(payments, never()).tryClaim(anyLong(), anyInt(), anyString(), any(Instant.class));
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void otherReservationEventsAreIgnoredWithoutTouchingProcessedEvents() {
        UUID eventId = UUID.randomUUID();

        service.process(envelope(eventId, "reservation.ReservationExpired", 109L, 45000), CORRELATION_ID);

        verify(processedEvents, never()).tryClaim(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void blankEventTypeIsRejectedAsMalformed() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() ->
                        service.process(new EventEnvelope<>(eventId, "  ", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 110L), payload(110L, 45000)), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        verify(processedEvents, never()).tryClaim(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void nullEventIdIsRejectedAsMalformed() {
        assertThatThrownBy(() ->
                        service.process(new EventEnvelope<>(null, "reservation.ReservationCreated", Instant.now(),
                                CORRELATION_ID, new UUID(0L, 111L), payload(111L, 45000)), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        verify(payments, never()).tryClaim(anyLong(), anyInt(), anyString(), any(Instant.class));
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void nullPayloadIsRejectedAsMalformed() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 112L), null),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verify(processedEvents, never()).tryClaim(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void negativeAmountIsRejectedInsteadOfSucceeding() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        envelope(eventId, "reservation.ReservationCreated", 113L, -100), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountCents");

        verify(processedEvents, never()).tryClaim(any());
        verify(payments, never()).tryClaim(anyLong(), anyInt(), anyString(), any(Instant.class));
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void missingAmountCentsIsRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 114L,
                "customerId", 99L,
                "eventId", 7L,
                "seatIds", List.of(10L)));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 114L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountCents");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void missingReservationIdIsRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "customerId", 99L,
                "eventId", 7L,
                "seatIds", List.of(10L),
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 115L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void zeroReservationIdIsRejected() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        envelope(eventId, "reservation.ReservationCreated", 0L, 45000), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void missingCustomerIdIsRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 116L,
                "eventId", 7L,
                "seatIds", List.of(10L),
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 116L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    void missingEventIdFieldIsRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 117L,
                "customerId", 99L,
                "seatIds", List.of(10L),
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 117L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void emptySeatIdsAreRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 118L,
                "customerId", 99L,
                "eventId", 7L,
                "seatIds", List.of(),
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 118L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seatIds");
    }

    @Test
    void missingSeatIdsAreRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 119L,
                "customerId", 99L,
                "eventId", 7L,
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 119L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seatIds");
    }

    @Test
    void seatIdsContainingNonPositiveIdsAreRejected() {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "reservationId", 120L,
                "customerId", 99L,
                "eventId", 7L,
                "seatIds", List.of(10L, 0L),
                "amountCents", 45000));

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "reservation.ReservationCreated", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 120L), payload),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seatIds");
    }

    @Test
    void secondEventForSameReservationIsADeterministicNoOp() {
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();

        when(processedEvents.tryClaim(firstEventId)).thenReturn(1);
        when(payments.tryClaim(eq(121L), eq(45000), eq("SUCCEEDED"), any(Instant.class))).thenReturn(1);
        when(payments.findByReservationId(121L))
                .thenReturn(Optional.of(settledPayment(47L, 121L, 45000, PaymentStatus.SUCCEEDED)));
        service.process(envelope(firstEventId, "reservation.ReservationCreated", 121L, 45000), CORRELATION_ID);

        // A different eventId for the same reservation: the event is still claimed as
        // processed, but no second payment/outbox row is created and nothing is thrown.
        when(processedEvents.tryClaim(secondEventId)).thenReturn(1);
        when(payments.tryClaim(eq(121L), eq(50000), eq("FAILED"), any(Instant.class))).thenReturn(0);

        service.process(envelope(secondEventId, "reservation.ReservationCreated", 121L, 50000), CORRELATION_ID);

        verify(processedEvents).tryClaim(firstEventId);
        verify(processedEvents).tryClaim(secondEventId);
        verify(payments).tryClaim(eq(121L), eq(50000), eq("FAILED"), any(Instant.class));
        verify(outbox, times(1)).save(any(OutboxEventEntity.class));
    }

    private PaymentEntity settledPayment(Long id, long reservationId, int amountCents, PaymentStatus status) {
        PaymentEntity payment = new PaymentEntity(reservationId, amountCents, status);
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "settledAt", Instant.now());
        return payment;
    }

    private EventEnvelope<JsonNode> envelope(UUID eventId, String eventType, long reservationId, int amountCents) {
        return new EventEnvelope<>(
                eventId, eventType, Instant.now(), CORRELATION_ID, new UUID(0L, reservationId), payload(reservationId, amountCents));
    }

    private JsonNode payload(long reservationId, int amountCents) {
        return objectMapper.valueToTree(new JpaPaymentProcessingService.ReservationCreatedPayload(
                reservationId, 99L, 7L, List.of(10L), amountCents));
    }
}
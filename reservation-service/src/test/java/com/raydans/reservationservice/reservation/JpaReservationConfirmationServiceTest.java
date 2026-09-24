package com.raydans.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import com.raydans.reservationservice.web.SeatStatus;
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
class JpaReservationConfirmationServiceTest {

    static final String CORRELATION_ID = "test-correlation-id";

    @Mock
    ProcessedEventRepository processedEvents;

    @Mock
    ReservationRepository reservations;

    @Mock
    SeatRepository seats;

    @Mock
    OutboxEventRepository outbox;

    final ObjectMapper objectMapper = new ObjectMapper();

    JpaReservationConfirmationService service;

    EventEntity event = newEvent(7L);

    @BeforeEach
    void setUp() {
        service = new JpaReservationConfirmationService(processedEvents, reservations, seats, outbox, objectMapper);
    }

    @Test
    void succeededOutcomeConfirmsReservationSellsSeatsAndWritesOutboxRow() {
        UUID eventId = UUID.randomUUID();
        ReservationEntity reservation = pendingReservation(42L, List.of(seat(10L, 15000), seat(11L, 12000)));
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(reservations.findById(42L)).thenReturn(Optional.of(reservation));

        service.process(succeededEnvelope(eventId, 42L), CORRELATION_ID);

        verify(processedEvents).tryClaim(eventId);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getSeats()).allMatch(s -> s.getStatus() == SeatStatus.SOLD);
        assertThat(reservation.getSeats()).allMatch(s -> s.getHoldExpiresAt() == null);
        verify(reservations).save(reservation);
        verify(seats).saveAll(any());

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEventEntity confirmed = outboxCaptor.getValue();
        assertThat(confirmed.getEventType()).isEqualTo("reservation.ReservationConfirmed");
        assertThat(confirmed.getAggregateType()).isEqualTo("Reservation");
        assertThat(confirmed.getAggregateId()).isEqualTo(42L);
        assertThat(confirmed.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(confirmed.getPayload())
                .contains("\"reservationId\":42")
                .contains("\"customerId\":99")
                .contains("\"eventId\":7")
                .contains("\"seatIds\":[10,11]")
                .contains("\"amountCents\":27000");
    }

    @Test
    void outcomeForNonPendingReservationIsANoOpButStillRecordedProcessed() {
        UUID eventId = UUID.randomUUID();
        ReservationEntity reservation = pendingReservation(43L, List.of(seat(12L, 1000)));
        reservation.markExpired();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(reservations.findById(43L)).thenReturn(Optional.of(reservation));

        service.process(succeededEnvelope(eventId, 43L), CORRELATION_ID);

        verify(processedEvents).tryClaim(eventId);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getSeats()).allMatch(s -> s.getStatus() == SeatStatus.HELD);
        verify(reservations, never()).save(any());
        verify(seats, never()).saveAll(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(0);

        service.process(succeededEnvelope(eventId, 44L), CORRELATION_ID);

        verify(reservations, never()).findById(any());
        verify(reservations, never()).save(any());
        verify(seats, never()).saveAll(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void failedOutcomeEventTypeIsIgnored() {
        UUID eventId = UUID.randomUUID();

        service.process(failedEnvelope(eventId, 45L), CORRELATION_ID);

        verify(processedEvents, never()).tryClaim(any());
        verify(reservations, never()).findById(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void paymentSucceededWithInconsistentStatusIsRejectedNotSilentlyAccepted() {
        UUID eventId = UUID.randomUUID();

        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                eventId, "payment.PaymentSucceeded", Instant.now(), CORRELATION_ID,
                new UUID(0L, 45L), objectMapper.valueToTree(Map.of(
                        "paymentId", 1L, "reservationId", 45L, "amountCents", 27000, "status", "FAILED")));

        assertThatThrownBy(() -> service.process(envelope, CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry status 'SUCCEEDED'");

        verify(processedEvents, never()).tryClaim(any());
        verify(reservations, never()).findById(any());
    }

    @Test
    void unknownEventTypeIsRejectedSoItCanBeRetriedAndDeadLettered() {
        UUID eventId = UUID.randomUUID();

        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                eventId, "pipeline.UnknownThing", Instant.now(), CORRELATION_ID,
                new UUID(0L, 45L), outcomePayload(45L));

        assertThatThrownBy(() -> service.process(envelope, CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported event type");

        verify(processedEvents, never()).tryClaim(any());
        verify(reservations, never()).findById(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void blankEventTypeIsRejectedAsMalformed() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "  ", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 46L), outcomePayload(46L)),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void nullEventIdIsRejectedAsMalformed() {
        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(null, "payment.PaymentSucceeded", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 47L), outcomePayload(47L)),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void nullPayloadIsRejectedAsMalformed() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "payment.PaymentSucceeded", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 48L), null),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void missingReservationIdIsRejected() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        new EventEnvelope<>(eventId, "payment.PaymentSucceeded", Instant.now(), CORRELATION_ID,
                                new UUID(0L, 49L), objectMapper.valueToTree(Map.of("paymentId", 1L))),
                        CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void zeroReservationIdIsRejected() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.process(
                        succeededEnvelope(eventId, 0L), CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void outcomeForUnknownReservationIsAnError() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(reservations.findById(50L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(succeededEnvelope(eventId, 50L), CORRELATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown reservation 50");

        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    private ReservationEntity pendingReservation(long id, List<SeatEntity> seatList) {
        ReservationEntity reservation =
                new ReservationEntity(99L, event, ReservationStatus.PENDING_PAYMENT, Instant.now().plusSeconds(600));
        reservation.getSeats().addAll(seatList);
        setId(reservation, id);
        return reservation;
    }

    private SeatEntity seat(long id, int priceCents) {
        SeatEntity seat = new SeatEntity(event, "Orchestra", "A", (int) id, priceCents, SeatStatus.HELD);
        setId(seat, id);
        return seat;
    }

    private EventEntity newEvent(long id) {
        EventEntity event = new EventEntity("Opening Night", "Metropolitan Opera", Instant.parse("2026-11-01T19:30:00Z"));
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private void setId(Object entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    private EventEnvelope<JsonNode> succeededEnvelope(UUID eventId, long reservationId) {
        return new EventEnvelope<>(
                eventId, "payment.PaymentSucceeded", Instant.now(), CORRELATION_ID,
                new UUID(0L, reservationId), outcomePayload(reservationId));
    }

    private EventEnvelope<JsonNode> failedEnvelope(UUID eventId, long reservationId) {
        return new EventEnvelope<>(
                eventId, "payment.PaymentFailed", Instant.now(), CORRELATION_ID,
                new UUID(0L, reservationId), outcomePayload(reservationId));
    }

    private JsonNode outcomePayload(long reservationId) {
        return objectMapper.valueToTree(Map.of(
                "paymentId", 1L, "reservationId", reservationId, "amountCents", 27000, "status", "SUCCEEDED"));
    }
}
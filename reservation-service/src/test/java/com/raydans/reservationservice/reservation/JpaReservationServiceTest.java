package com.raydans.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.ResourceNotFoundException;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import com.raydans.reservationservice.web.ReservationRequest;
import com.raydans.reservationservice.web.ReservationResponse;
import com.raydans.reservationservice.web.SeatStatus;
import com.raydans.reservationservice.web.SeatUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JpaReservationServiceTest {

    static final String CORRELATION_ID = "test-correlation-id";

    @Mock
    ReservationRepository reservations;

    @Mock
    SeatRepository seats;

    @Mock
    OutboxEventRepository outbox;

    JpaReservationService service;

    EventEntity event = newEvent(7L);

    @BeforeEach
    void setUp() {
        service = new JpaReservationService(reservations, seats, outbox, new ObjectMapper());
        MDC.put("correlationId", CORRELATION_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove("correlationId");
    }

    @Test
    void createHoldsAllSeatsCreatesPendingReservationAndWritesOutboxRow() {
        SeatEntity seat1 = seat(10L, "Orchestra", "A", 1, 15000);
        SeatEntity seat2 = seat(11L, "Orchestra", "B", 2, 12000);
        when(seats.findAllById(List.of(10L, 11L))).thenReturn(List.of(seat1, seat2));
        when(reservations.saveAndFlush(any(ReservationEntity.class)))
                .thenAnswer(invocation -> {
                    ReservationEntity r = invocation.getArgument(0);
                    ReflectionTestUtils.setField(r, "id", 42L);
                    return r;
                });

        ReservationResponse response = service.create(new ReservationRequest(7L, List.of(10L, 11L)), 99L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.customerId()).isEqualTo(99L);
        assertThat(response.eventId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(response.amountCents()).isEqualTo(27000);
        assertThat(response.seats()).hasSize(2);
        assertThat(seat1.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat2.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat1.getHoldExpiresAt()).isAfter(Instant.now());
        assertThat(seat2.getHoldExpiresAt()).isEqualTo(seat1.getHoldExpiresAt());
        verify(seats, never()).save(any(SeatEntity.class));

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEventEntity eventRow = outboxCaptor.getValue();
        assertThat(eventRow.getEventType()).isEqualTo("reservation.ReservationCreated");
        assertThat(eventRow.getAggregateId()).isEqualTo(42L);
        assertThat(eventRow.getAggregateType()).isEqualTo("Reservation");
        assertThat(eventRow.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(eventRow.getPayload())
                .contains("\"reservationId\":42")
                .contains("\"customerId\":99")
                .contains("\"eventId\":7")
                .contains("\"seatIds\":[10,11]")
                .contains("\"amountCents\":27000");
    }

    @Test
    void createWithRequestedSeatMissingRejectsAndHoldsNothing() {
        when(seats.findAllById(List.of(10L, 55L))).thenReturn(List.of(seat(10L, "Orchestra", "A", 1, 15000)));

        assertThatThrownBy(() -> service.create(new ReservationRequest(7L, List.of(10L, 55L)), 99L))
                .isInstanceOf(SeatUnavailableException.class);
        verify(seats, never()).saveAll(any());
        verify(reservations, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void createWithTakenSeatRejectsAndHoldsNothing() {
        SeatEntity taken = seat(10L, "Orchestra", "A", 1, 15000);
        taken.flipToHeld(Instant.now().plusSeconds(600));
        when(seats.findAllById(List.of(10L))).thenReturn(List.of(taken));

        assertThatThrownBy(() -> service.create(new ReservationRequest(7L, List.of(10L)), 99L))
                .isInstanceOf(SeatUnavailableException.class);
        verify(seats, never()).saveAll(any());
        verify(reservations, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void createWithSeatFromAnotherEventRejects() {
        SeatEntity foreign = seat(10L, "Orchestra", "A", 1, 15000);
        ReflectionTestUtils.setField(foreign, "event", newEvent(8L));
        when(seats.findAllById(List.of(10L))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.create(new ReservationRequest(7L, List.of(10L)), 99L))
                .isInstanceOf(SeatUnavailableException.class);
        verify(seats, never()).saveAll(any());
    }

    @Test
    void createLoserOfVersionConflictGetsSeatUnavailableAndPersistsNothing() {
        SeatEntity seat1 = seat(10L, "Orchestra", "A", 1, 15000);
        SeatEntity seat2 = seat(11L, "Orchestra", "B", 2, 12000);
        when(seats.findAllById(List.of(10L, 11L))).thenReturn(List.of(seat1, seat2));
        when(reservations.saveAndFlush(any(ReservationEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("Race on seat 10", new Object[] {10L}));

        assertThatThrownBy(() -> service.create(new ReservationRequest(7L, List.of(10L, 11L)), 99L))
                .isInstanceOf(SeatUnavailableException.class);

        verify(seats, never()).save(any());
        verify(seats, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void createWithNoSeatsRejectsBeforeAnyPersist() {
        assertThatThrownBy(() -> service.create(new ReservationRequest(7L, List.of()), 99L))
                .isInstanceOf(SeatUnavailableException.class);
        verify(seats, never()).findAllById(any());
        verify(reservations, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void getReturnsMappedReservation() {
        when(reservations.findById(42L)).thenReturn(Optional.of(reservationEntity(42L)));

        ReservationResponse response = service.get(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.customerId()).isEqualTo(99L);
        assertThat(response.eventId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(response.amountCents()).isEqualTo(27000);
        assertThat(response.seats()).extracting("id").containsExactly(10L, 11L);
        verify(reservations, never()).save(any());
    }

    @Test
    void getOfOverduePendingReservationExpiresItBeforeReturning() {
        ReservationEntity overdue = reservationEntity(42L);
        overdue.getSeats().clear();
        ReflectionTestUtils.setField(overdue, "expiresAt", Instant.now().minusSeconds(1));
        when(reservations.findById(42L)).thenReturn(Optional.of(overdue));

        ReservationResponse response = service.get(42L);

        assertThat(response.status()).isEqualTo(ReservationStatus.EXPIRED);
        ArgumentCaptor<ReservationEntity> captor = ArgumentCaptor.forClass(ReservationEntity.class);
        verify(reservations).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void listByCustomerIdExpiresOverduePendingReservations() {
        ReservationEntity overdue = reservationEntity(42L);
        ReflectionTestUtils.setField(overdue, "expiresAt", Instant.now().minusSeconds(1));
        ReservationEntity fresh = reservationEntity(43L);
        when(reservations.findByCustomerIdOrderByIdDesc(99L)).thenReturn(List.of(overdue, fresh));

        List<ReservationResponse> responses = service.listByCustomerId(99L);

        assertThat(responses).extracting(ReservationResponse::status)
                .containsExactly(ReservationStatus.EXPIRED, ReservationStatus.PENDING_PAYMENT);
        ArgumentCaptor<ReservationEntity> captor = ArgumentCaptor.forClass(ReservationEntity.class);
        verify(reservations).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void getForUnknownReservationThrowsNotFound() {
        when(reservations.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(404L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByCustomerIdReturnsOnlyThatCustomersReservations() {
        when(reservations.findByCustomerIdOrderByIdDesc(99L))
                .thenReturn(List.of(reservationEntity(42L), reservationEntity(43L)));

        List<ReservationResponse> responses = service.listByCustomerId(99L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).customerId()).isEqualTo(99L);
        assertThat(responses).extracting(ReservationResponse::id).containsExactly(42L, 43L);
    }

    private SeatEntity seat(long id, String section, String row, int number, int priceCents) {
        SeatEntity entity = new SeatEntity(event, section, row, number, priceCents, SeatStatus.AVAILABLE);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static EventEntity newEvent(long id) {
        EventEntity e = new EventEntity("Opening Night", "Metropolitan Opera", Instant.parse("2026-11-01T19:30:00Z"));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private ReservationEntity reservationEntity(long id) {
        ReservationEntity r = new ReservationEntity(
                99L, event, ReservationStatus.PENDING_PAYMENT, Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(r, "id", id);
        r.getSeats().addAll(List.of(
                seat(10L, "Orchestra", "A", 1, 15000),
                seat(11L, "Orchestra", "B", 2, 12000)));
        return r;
    }
}
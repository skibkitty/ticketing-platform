package com.raydans.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.web.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldExpirerTest {

    @Mock
    SeatRepository seats;

    @Mock
    ReservationRepository reservations;

    HoldExpirer expirer;

    @BeforeEach
    void setUp() {
        expirer = new HoldExpirer(seats, reservations);
    }

    @Test
    void releasesOverdueHeldSeatsBackToAvailable() {
        SeatEntity overdue = seat(10L, SeatStatus.HELD);
        overdue.flipToHeld(Instant.now().minusSeconds(1));
        when(seats.findByStatusAndHoldExpiresAtBefore(org.mockito.ArgumentMatchers.eq(SeatStatus.HELD), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(overdue));
        when(reservations.findByStatusAndExpiresAtBefore(org.mockito.ArgumentMatchers.eq(ReservationStatus.PENDING_PAYMENT),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of());

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(overdue.getHoldExpiresAt()).isNull();
        verify(seats).saveAll(List.of(overdue));
    }

    @Test
    void marksOverduePendingReservationsExpired() {
        ReservationEntity overdue = new ReservationEntity(
                99L, event(7L), ReservationStatus.PENDING_PAYMENT, Instant.now().minusSeconds(1));
        when(seats.findByStatusAndHoldExpiresAtBefore(org.mockito.ArgumentMatchers.eq(SeatStatus.HELD), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());
        when(reservations.findByStatusAndExpiresAtBefore(org.mockito.ArgumentMatchers.eq(ReservationStatus.PENDING_PAYMENT),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(overdue));

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(reservations).saveAll(List.of(overdue));
    }

    @Test
    void doesNothingWhenNothingIsOverdue() {
        when(seats.findByStatusAndHoldExpiresAtBefore(org.mockito.ArgumentMatchers.eq(SeatStatus.HELD), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());
        when(reservations.findByStatusAndExpiresAtBefore(org.mockito.ArgumentMatchers.eq(ReservationStatus.PENDING_PAYMENT),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of());

        expirer.expire();

        verify(seats, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reservations, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    private SeatEntity seat(long id, SeatStatus status) {
        SeatEntity entity = new SeatEntity(event(7L), "Orchestra", "A", 1, 15000, status);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static EventEntity event(long id) {
        EventEntity e = new EventEntity(
                "Opening Night", "Metropolitan Opera", Instant.parse("2026-11-01T19:30:00Z"));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
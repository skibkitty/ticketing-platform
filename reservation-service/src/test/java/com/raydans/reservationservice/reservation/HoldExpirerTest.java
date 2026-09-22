package com.raydans.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    void expiresOverdueReservationAndReleasesExactlyItsLapsedSeats() {
        SeatEntity seat = heldSeat(10L, Instant.now().minusSeconds(1));
        ReservationEntity overdue =
                pendingReservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldExpiresAt()).isNull();
        verify(reservations).saveAll(List.of(overdue));
        verify(seats).saveAll(List.of(seat));
    }

    @Test
    void doesNotReleaseSeatWhoseHoldIsStillLive() {
        SeatEntity seat = heldSeat(10L, Instant.now().plusSeconds(600));
        ReservationEntity overdue =
                pendingReservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(reservations).saveAll(List.of(overdue));
        verify(seats, never()).saveAll(any());
    }

    @Test
    void doesNothingWhenNoReservationIsOverdue() {
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of());

        expirer.expire();

        verify(reservations, never()).saveAll(any());
        verify(seats, never()).saveAll(any());
    }

    private static SeatEntity heldSeat(long id, Instant holdExpiresAt) {
        SeatEntity entity = new SeatEntity(event(7L), "Orchestra", "A", 1, 15000, SeatStatus.HELD);
        ReflectionTestUtils.setField(entity, "id", id);
        entity.flipToHeld(holdExpiresAt);
        return entity;
    }

    private static ReservationEntity pendingReservation(long id, List<SeatEntity> heldSeats, Instant expiresAt) {
        ReservationEntity entity = new ReservationEntity(
                99L, event(7L), ReservationStatus.PENDING_PAYMENT, expiresAt);
        ReflectionTestUtils.setField(entity, "id", id);
        entity.getSeats().addAll(heldSeats);
        return entity;
    }

    private static EventEntity event(long id) {
        EventEntity e = new EventEntity(
                "Opening Night", "Metropolitan Opera", Instant.parse("2026-11-01T19:30:00Z"));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
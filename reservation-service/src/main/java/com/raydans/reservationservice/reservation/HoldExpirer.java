package com.raydans.reservationservice.reservation;

import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.web.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HoldExpirer {

    private final SeatRepository seats;
    private final ReservationRepository reservations;

    public HoldExpirer(SeatRepository seats, ReservationRepository reservations) {
        this.seats = seats;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${app.hold.expire-interval-ms:1000}")
    @Transactional
    public void expire() {
        Instant now = Instant.now();

        List<SeatEntity> overdueSeats = seats.findByStatusAndHoldExpiresAtBefore(SeatStatus.HELD, now);
        for (SeatEntity seat : overdueSeats) {
            seat.releaseHold();
        }
        if (!overdueSeats.isEmpty()) {
            seats.saveAll(overdueSeats);
        }

        List<ReservationEntity> overdueReservations =
                reservations.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING_PAYMENT, now);
        for (ReservationEntity reservation : overdueReservations) {
            reservation.markExpired();
        }
        if (!overdueReservations.isEmpty()) {
            reservations.saveAll(overdueReservations);
        }
    }
}
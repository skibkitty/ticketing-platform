package com.raydans.reservationservice.reservation;

import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import java.time.Instant;
import java.util.ArrayList;
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
        List<ReservationEntity> overdue =
                reservations.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING_PAYMENT, now);
        if (overdue.isEmpty()) {
            return;
        }

        List<SeatEntity> releasedSeats = new ArrayList<>();
        for (ReservationEntity reservation : overdue) {
            reservation.markExpired();
            for (SeatEntity seat : reservation.getSeats()) {
                if (seat.releaseHoldIfLapsed(now)) {
                    releasedSeats.add(seat);
                }
            }
        }

        reservations.saveAll(overdue);
        if (!releasedSeats.isEmpty()) {
            seats.saveAll(releasedSeats);
        }
    }
}
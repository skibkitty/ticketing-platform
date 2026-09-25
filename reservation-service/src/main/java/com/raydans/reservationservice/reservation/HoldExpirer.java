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
    private final ReservationExpiryService expiry;

    public HoldExpirer(
            SeatRepository seats,
            ReservationRepository reservations,
            ReservationExpiryService expiry) {
        this.seats = seats;
        this.reservations = reservations;
        this.expiry = expiry;
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

        // Each transition stages its ReservationExpired outbox row in this same transaction, so a
        // @Version-conflict rollback (a concurrent re-hold or confirmation winning the seat or the
        // reservation) undoes the EXPIRED mutation, the seat releases, and the staged row together.
        List<SeatEntity> releasedSeats = new ArrayList<>();
        for (ReservationEntity reservation : overdue) {
            releasedSeats.addAll(expiry.expireIfOverdue(reservation, now));
        }

        reservations.saveAll(overdue);
        if (!releasedSeats.isEmpty()) {
            seats.saveAll(releasedSeats);
        }
    }
}
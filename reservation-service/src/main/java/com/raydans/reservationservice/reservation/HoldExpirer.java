package com.raydans.reservationservice.reservation;

import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
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
            // The sweep acts for no request, so each expiry is given an id of its own: the one
            // its log line, its ReservationExpired and the Notification it causes all carry.
            MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
            try {
                releasedSeats.addAll(expiry.expireIfOverdue(reservation, now));
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }

        reservations.saveAll(overdue);
        if (!releasedSeats.isEmpty()) {
            seats.saveAll(releasedSeats);
        }
    }
}
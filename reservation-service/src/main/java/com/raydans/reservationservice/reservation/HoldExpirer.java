package com.raydans.reservationservice.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
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

    static final String RESERVATION_EXPIRED = "reservation.ReservationExpired";

    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public HoldExpirer(
            SeatRepository seats,
            ReservationRepository reservations,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper) {
        this.seats = seats;
        this.reservations = reservations;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
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
            // A seat is only released if its hold has lapsed in this snapshot. If the seat was
            // re-held concurrently after this snapshot, the @Version check on saveAll rejects the
            // expired release (ObjectOptimisticLockingFailureException), the whole transaction
            // rolls back, and the next tick re-evaluates against the newer state — a re-hold is
            // never overwritten.
            List<SeatEntity> lapsed = new ArrayList<>();
            for (SeatEntity seat : reservation.getSeats()) {
                if (seat.releaseHoldIfLapsed(now)) {
                    lapsed.add(seat);
                }
            }
            releasedSeats.addAll(lapsed);
            outbox.save(expiredEvent(reservation, lapsed));
        }

        reservations.saveAll(overdue);
        if (!releasedSeats.isEmpty()) {
            seats.saveAll(releasedSeats);
        }
    }

    private OutboxEventEntity expiredEvent(
            ReservationEntity reservation, List<SeatEntity> releasedSeats) {
        int amountCents = releasedSeats.stream().mapToInt(SeatEntity::getPriceCents).sum();
        try {
            String payload = objectMapper.writeValueAsString(new ReservationExpiredPayload(
                    reservation.getId(),
                    reservation.getCustomerId(),
                    reservation.getEvent().getId(),
                    releasedSeats.stream().map(SeatEntity::getId).toList(),
                    amountCents));
            return new OutboxEventEntity(
                    UUID.randomUUID(),
                    "Reservation",
                    reservation.getId(),
                    RESERVATION_EXPIRED,
                    payload,
                    currentCorrelationId());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize ReservationExpired payload", ex);
        }
    }

    private String currentCorrelationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        return cid == null ? UUID.randomUUID().toString() : cid;
    }

    /** Wire payload of a {@code reservation.ReservationExpired} event. */
    public record ReservationExpiredPayload(
            long reservationId,
            long customerId,
            long eventId,
            List<Long> seatIds,
            int amountCents) {}
}
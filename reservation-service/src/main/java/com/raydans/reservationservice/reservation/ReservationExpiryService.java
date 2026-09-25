package com.raydans.reservationservice.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code PENDING_PAYMENT -> EXPIRED} transition so every trigger — the scheduled sweep
 * ({@link HoldExpirer}) and the read path ({@link JpaReservationService#get} /
 * {@code listByCustomerId}) — behaves identically: in one transaction the reservation is marked
 * EXPIRED, exactly the seats whose holds have actually lapsed are released, and exactly one
 * {@code reservation.ReservationExpired} outbox row (with the current correlation id) is staged.
 *
 * <p>The {@code @Version} gates on Reservation and Seat make this safely re-runnable: the first
 * caller to commit wins the version bump, and a racing or later caller sees the reservation is no
 * longer PENDING_PAYMENT and publishes nothing — two expirers, or an expirer racing a read-path
 * expiry, can never emit duplicate (or leave out either) event. A {@code @Version}-conflict
 * rollback undoes the EXPIRED mutation, the seat releases, and the staged outbox row together.
 *
 * <p>Contract: the event represents the lifecycle transition to EXPIRED, while seatIds/amountCents
 * describe the seats actually released by this transition — which may be none (a concurrently
 * re-held seat is another holder's now and is never listed, mirroring the ReservationCancelled
 * releaseHold convention in ADR 007).
 */
@Service
class ReservationExpiryService {

    static final String RESERVATION_EXPIRED = "reservation.ReservationExpired";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    ReservationExpiryService(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    boolean isOverdue(ReservationEntity reservation, Instant now) {
        return reservation.getStatus() == ReservationStatus.PENDING_PAYMENT
                && reservation.getExpiresAt().isBefore(now);
    }

    /**
     * Expires {@code reservation} if it is overdue: marks it EXPIRED, releases exactly the seats
     * whose holds lapsed in this snapshot, and stages exactly one ReservationExpired outbox row in
     * the caller's transaction. Returns the released seats (empty when the reservation was not
     * overdue, or when no seat's hold has lapsed). Re-runnable: once the reservation is EXPIRED the
     * guard returns false and nothing is staged again.
     */
    List<SeatEntity> expireIfOverdue(ReservationEntity reservation, Instant now) {
        if (!isOverdue(reservation, now)) {
            return List.of();
        }
        reservation.markExpired();
        List<SeatEntity> releasedSeats = new ArrayList<>();
        for (SeatEntity seat : reservation.getSeats()) {
            if (seat.releaseHoldIfLapsed(now)) {
                releasedSeats.add(seat);
            }
        }
        outbox.save(expiredEvent(reservation, releasedSeats));
        return releasedSeats;
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
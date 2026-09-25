package com.raydans.reservationservice.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money-step outcome consumer: {@code payment.PaymentSucceeded} confirms a pending
 * reservation and sells its seats; {@code payment.PaymentFailed} is the T07 compensating
 * action — it cancels the reservation and releases its seats back to {@code AVAILABLE}. Both
 * run in the same transaction that claims the event in {@code processed_events} (ADR 004),
 * write the {@code ReservationConfirmed}/{@code ReservationCancelled} outbox row, and are
 * gated on the reservation still being {@code PENDING_PAYMENT} (ADR 007).
 */
@Service
class JpaReservationConfirmationService implements ReservationConfirmationService {

    static final String PAYMENT_SUCCEEDED = "payment.PaymentSucceeded";
    static final String PAYMENT_FAILED = "payment.PaymentFailed";
    static final String PAYMENT_SUCCEEDED_STATUS = "SUCCEEDED";
    static final String PAYMENT_FAILED_STATUS = "FAILED";
    static final String RESERVATION_CONFIRMED = "reservation.ReservationConfirmed";
    static final String RESERVATION_CANCELLED = "reservation.ReservationCancelled";

    private static final Logger log = LoggerFactory.getLogger(JpaReservationConfirmationService.class);

    private final ProcessedEventRepository processedEvents;
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    JpaReservationConfirmationService(
            ProcessedEventRepository processedEvents,
            ReservationRepository reservations,
            SeatRepository seats,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.seats = seats;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void process(EventEnvelope<JsonNode> envelope, String correlationId) {
        String eventType = envelope.eventType();
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("envelope.eventType must be set, got: " + envelope);
        }
        switch (eventType) {
            case PAYMENT_SUCCEEDED -> processPaymentSucceeded(envelope, correlationId);
            case PAYMENT_FAILED -> processPaymentFailed(envelope, correlationId);
            // Anything else is an unrecognized event on our topic: reject it deliberately so
            // the record goes through the retry/DLT path (ADR 008) instead of vanishing.
            default -> throw new IllegalArgumentException(
                    "Unsupported event type on payment.events.v1: " + eventType);
        }
    }

    private void processPaymentSucceeded(EventEnvelope<JsonNode> envelope, String correlationId) {
        String eventType = envelope.eventType();
        if (envelope.eventId() == null) {
            throw new IllegalArgumentException("envelope.eventId must be set for " + eventType);
        }
        if (envelope.payload() == null) {
            throw new IllegalArgumentException("envelope.payload must be set for " + eventType);
        }
        PaymentOutcomePayload outcome = parseOutcome(envelope.payload());
        validateSucceededOutcome(outcome);

        // Idempotency claim (ADR 004): 1 = this transaction won the event, 0 = duplicate.
        if (processedEvents.tryClaim(envelope.eventId()) == 0) {
            log.debug("Skipping duplicate delivery of event {}", envelope.eventId());
            return;
        }

        ReservationEntity reservation = reservations.findById(outcome.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "PaymentSucceeded for unknown reservation " + outcome.reservationId()));
        confirmIfPending(reservation, envelope.eventId(), correlationId);
    }

    private void processPaymentFailed(EventEnvelope<JsonNode> envelope, String correlationId) {
        String eventType = envelope.eventType();
        if (envelope.eventId() == null) {
            throw new IllegalArgumentException("envelope.eventId must be set for " + eventType);
        }
        if (envelope.payload() == null) {
            throw new IllegalArgumentException("envelope.payload must be set for " + eventType);
        }
        PaymentOutcomePayload outcome = parseOutcome(envelope.payload());
        validateFailedOutcome(outcome);

        // Idempotency claim (ADR 004): 1 = this transaction won the event, 0 = duplicate.
        if (processedEvents.tryClaim(envelope.eventId()) == 0) {
            log.debug("Skipping duplicate delivery of event {}", envelope.eventId());
            return;
        }

        ReservationEntity reservation = reservations.findById(outcome.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "PaymentFailed for unknown reservation " + outcome.reservationId()));
        cancelIfPending(reservation, envelope.eventId(), correlationId);
    }

    /**
     * ADR 007 guard: an outcome only applies while the reservation is still {@code
     * PENDING_PAYMENT}. Otherwise the event has already been claimed as processed and we no-op —
     * a late {@code PaymentSucceeded} must not re-brand a meanwhile-expired/cancelled reservation
     * or touch seats it no longer owns.
     */
    private void confirmIfPending(
            ReservationEntity reservation, UUID eventId, String correlationId) {
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            log.info(
                    "Ignoring PaymentSucceeded {} for reservation {} with status {} (ADR 007 guard); "
                            + "event recorded as processed",
                    eventId, reservation.getId(), reservation.getStatus());
            return;
        }

        reservation.confirm();
        List<SeatEntity> soldSeats = reservation.getSeats().stream()
                .peek(SeatEntity::markSold)
                .toList();
        reservations.save(reservation);
        seats.saveAll(soldSeats);
        outbox.save(confirmedEvent(reservation, soldSeats, correlationId));

        log.info(
                "Reservation {} confirmed by payment outcome {}",
                reservation.getId(), eventId);
    }

    /**
     * ADR 007 guard, on the compensating side: a {@code PaymentFailed} only applies while the
     * reservation is still {@code PENDING_PAYMENT}. Otherwise the event has already been claimed
     * as processed and we no-op — a late {@code PaymentFailed} must not re-brand a
     * meanwhile-confirmed/expired reservation or touch seats it no longer owns.
     */
    private void cancelIfPending(
            ReservationEntity reservation, UUID eventId, String correlationId) {
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            log.info(
                    "Ignoring PaymentFailed {} for reservation {} with status {} (ADR 007 guard); "
                            + "event recorded as processed",
                    eventId, reservation.getId(), reservation.getStatus());
            return;
        }

        reservation.cancel();
        // releaseHold() only releases seats actually in HELD state (it returns whether it
        // released), so a seat that somehow left the hold is never handed back to the pool and
        // the outbox payload reflects exactly the seats that were released.
        List<SeatEntity> releasedSeats = reservation.getSeats().stream()
                .filter(SeatEntity::releaseHold)
                .toList();
        reservations.save(reservation);
        if (!releasedSeats.isEmpty()) {
            seats.saveAll(releasedSeats);
        }
        outbox.save(cancelledEvent(reservation, releasedSeats, correlationId));

        log.info(
                "Reservation {} cancelled by payment outcome {}",
                reservation.getId(), eventId);
    }

    private OutboxEventEntity confirmedEvent(
            ReservationEntity reservation, List<SeatEntity> soldSeats, String correlationId) {
        // The confirmation amount is derived from the reservation's own seat prices — the
        // source of truth is the reservation's pricing, never the payment event's amountCents
        // (which only documents what the money step settled).
        int amountCents = soldSeats.stream().mapToInt(SeatEntity::getPriceCents).sum();
        try {
            String payload = objectMapper.writeValueAsString(new ReservationConfirmedPayload(
                    reservation.getId(),
                    reservation.getCustomerId(),
                    reservation.getEvent().getId(),
                    soldSeats.stream().map(SeatEntity::getId).toList(),
                    amountCents));
            return new OutboxEventEntity(
                    UUID.randomUUID(),
                    "Reservation",
                    reservation.getId(),
                    RESERVATION_CONFIRMED,
                    payload,
                    correlationId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize ReservationConfirmed payload", ex);
        }
    }

    private OutboxEventEntity cancelledEvent(
            ReservationEntity reservation, List<SeatEntity> releasedSeats, String correlationId) {
        // Like confirmation, the amount is derived from the reservation's own seat prices —
        // the source of truth is the reservation's pricing, never the payment event's
        // amountCents (which only documents what the money step attempted to settle).
        int amountCents = releasedSeats.stream().mapToInt(SeatEntity::getPriceCents).sum();
        try {
            String payload = objectMapper.writeValueAsString(new ReservationCancelledPayload(
                    reservation.getId(),
                    reservation.getCustomerId(),
                    reservation.getEvent().getId(),
                    releasedSeats.stream().map(SeatEntity::getId).toList(),
                    amountCents));
            return new OutboxEventEntity(
                    UUID.randomUUID(),
                    "Reservation",
                    reservation.getId(),
                    RESERVATION_CANCELLED,
                    payload,
                    correlationId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize ReservationCancelled payload", ex);
        }
    }

    private PaymentOutcomePayload parseOutcome(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, PaymentOutcomePayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize payment outcome payload", ex);
        }
    }

    private void validateSucceededOutcome(PaymentOutcomePayload outcome) {
        validateCommon(outcome);
        // The event TYPE is authoritative proof the payment succeeded; the payload's status
        // field is only allowed to agree. A PaymentSucceeded whose payload claims anything
        // else (e.g. "FAILED") is a contract violation and must not be silently accepted.
        if (!PAYMENT_SUCCEEDED_STATUS.equals(outcome.status())) {
            throw new IllegalArgumentException(
                    "payment.PaymentSucceeded must carry status 'SUCCEEDED' but was: "
                            + outcome.status() + " (reservation " + outcome.reservationId() + ")");
        }
    }

    private void validateFailedOutcome(PaymentOutcomePayload outcome) {
        validateCommon(outcome);
        // Mirror of the succeeded check: a PaymentFailed whose payload claims anything but
        // "FAILED" disputes its own event type — a producer bug to be dead-lettered, never
        // silently accepted as a compensation.
        if (!PAYMENT_FAILED_STATUS.equals(outcome.status())) {
            throw new IllegalArgumentException(
                    "payment.PaymentFailed must carry status 'FAILED' but was: "
                            + outcome.status() + " (reservation " + outcome.reservationId() + ")");
        }
    }

    private void validateCommon(PaymentOutcomePayload outcome) {
        if (outcome.reservationId() == null) {
            throw new IllegalArgumentException("payment outcome reservationId must be set");
        }
        if (outcome.reservationId() <= 0) {
            throw new IllegalArgumentException(
                    "payment outcome reservationId must be a positive id: " + outcome.reservationId());
        }
    }

    /**
     * Wire payload of a {@code payment.PaymentSucceeded}/{@code payment.PaymentFailed} event
     * (payment-service contract). The event type itself is authoritative proof of the outcome;
     * {@code status} must agree ({@code SUCCEEDED} or {@code FAILED}) or the event is rejected.
     * {@code amountCents} is informational — the confirmation/cancellation amount is derived
     * from the reservation's seat prices. Only {@code reservationId} is actually needed.
     */
    public record PaymentOutcomePayload(
            Long paymentId, Long reservationId, Integer amountCents, String status) {}

    /** Wire payload of a {@code reservation.ReservationConfirmed} event. */
    public record ReservationConfirmedPayload(
            long reservationId, long customerId, long eventId, List<Long> seatIds, int amountCents) {}

    /** Wire payload of a {@code reservation.ReservationCancelled} event. */
    public record ReservationCancelledPayload(
            long reservationId, long customerId, long eventId, List<Long> seatIds, int amountCents) {}
}
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
 * reservation and sells its seats, in the same transaction that claims the event in
 * {@code processed_events} (ADR 004) and writes the {@code ReservationConfirmed} outbox row.
 */
@Service
class JpaReservationConfirmationService implements ReservationConfirmationService {

    static final String PAYMENT_SUCCEEDED = "payment.PaymentSucceeded";
    static final String RESERVATION_CONFIRMED = "reservation.ReservationConfirmed";

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
        // Everything else on payment.events.v1 (e.g. the PaymentFailed that T07 compensates,
        // or any unknown type) is deliberately ignored, unprocessed (ADR 008).
        if (!PAYMENT_SUCCEEDED.equals(eventType)) {
            log.debug("Ignoring {} on payment.events.v1", eventType);
            return;
        }

        if (envelope.eventId() == null) {
            throw new IllegalArgumentException("envelope.eventId must be set for " + eventType);
        }
        if (envelope.payload() == null) {
            throw new IllegalArgumentException("envelope.payload must be set for " + eventType);
        }
        PaymentSucceededPayload outcome = parseOutcome(envelope.payload());
        validateOutcome(outcome);

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

    private OutboxEventEntity confirmedEvent(
            ReservationEntity reservation, List<SeatEntity> soldSeats, String correlationId) {
        try {
            String payload = objectMapper.writeValueAsString(new ReservationConfirmedPayload(
                    reservation.getId(),
                    reservation.getCustomerId(),
                    reservation.getEvent().getId(),
                    soldSeats.stream().map(SeatEntity::getId).toList(),
                    soldSeats.stream().mapToInt(SeatEntity::getPriceCents).sum()));
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

    private PaymentSucceededPayload parseOutcome(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, PaymentSucceededPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize PaymentSucceeded payload", ex);
        }
    }

    private void validateOutcome(PaymentSucceededPayload outcome) {
        if (outcome.reservationId() == null) {
            throw new IllegalArgumentException("PaymentSucceeded reservationId must be set");
        }
        if (outcome.reservationId() <= 0) {
            throw new IllegalArgumentException(
                    "PaymentSucceeded reservationId must be a positive id: " + outcome.reservationId());
        }
    }

    /**
     * Wire payload of a {@code payment.PaymentSucceeded} event (payment-service contract). Only
     * {@code reservationId} is needed to confirm; the rest documents the resolved payment.
     */
    public record PaymentSucceededPayload(
            Long paymentId, Long reservationId, Integer amountCents, String status) {}

    /** Wire payload of a {@code reservation.ReservationConfirmed} event. */
    public record ReservationConfirmedPayload(
            long reservationId, long customerId, long eventId, List<Long> seatIds, int amountCents) {}
}
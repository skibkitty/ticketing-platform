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
    static final String PAYMENT_FAILED = "payment.PaymentFailed";
    static final String PAYMENT_SUCCEEDED_STATUS = "SUCCEEDED";
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
        switch (eventType) {
            case PAYMENT_SUCCEEDED -> processPaymentSucceeded(envelope, correlationId);
            // payment.PaymentFailed is owned by the T07 compensating step (reservation stays
            // PENDING_PAYMENT and lapses to EXPIRED instead): this confirmation consumer
            // deliberately ignores it — never claimed, never dead-lettered.
            case PAYMENT_FAILED -> log.debug(
                    "Ignoring {} on payment.events.v1 (owned by the T07 compensation step)",
                    eventType);
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
        // The event TYPE is authoritative proof the payment succeeded; the payload's status
        // field is only allowed to agree. A PaymentSucceeded whose payload claims anything
        // else (e.g. "FAILED") is a contract violation and must not be silently accepted.
        if (!PAYMENT_SUCCEEDED_STATUS.equals(outcome.status())) {
            throw new IllegalArgumentException(
                    "payment.PaymentSucceeded must carry status 'SUCCEEDED' but was: "
                            + outcome.status() + " (reservation " + outcome.reservationId() + ")");
        }
    }

    /**
     * Wire payload of a {@code payment.PaymentSucceeded} event (payment-service contract).
     * The event type itself is authoritative proof of a successful payment; {@code status}
     * must agree ({@code SUCCEEDED}) or the event is rejected. {@code amountCents} is
     * informational — the confirmation amount is derived from the reservation's seat
     * prices. Only {@code reservationId} is actually needed to confirm.
     */
    public record PaymentSucceededPayload(
            Long paymentId, Long reservationId, Integer amountCents, String status) {}

    /** Wire payload of a {@code reservation.ReservationConfirmed} event. */
    public record ReservationConfirmedPayload(
            long reservationId, long customerId, long eventId, List<Long> seatIds, int amountCents) {}
}
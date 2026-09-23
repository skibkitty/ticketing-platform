package com.raydans.paymentservice.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.paymentservice.outbox.OutboxEventEntity;
import com.raydans.paymentservice.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaPaymentProcessingService implements PaymentProcessingService {

    static final String RESERVATION_CREATED = "reservation.ReservationCreated";
    static final String PAYMENT_SUCCEEDED = "payment.PaymentSucceeded";
    static final String PAYMENT_FAILED = "payment.PaymentFailed";

    private static final Logger log = LoggerFactory.getLogger(JpaPaymentProcessingService.class);

    private final ProcessedEventRepository processedEvents;
    private final PaymentRepository payments;
    private final OutboxEventRepository outbox;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    JpaPaymentProcessingService(
            ProcessedEventRepository processedEvents,
            PaymentRepository payments,
            OutboxEventRepository outbox,
            PaymentProperties properties,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Applies a {@code reservation.ReservationCreated} to the Payment idempotently.
     *
     * <p>The guarantee is made by two atomic database claims, both inside the one
     * transaction that also writes the outbox row (ADR 004):
     *
     * <ol>
     *   <li>{@code processed_events} is claimed with {@code INSERT ... ON CONFLICT DO
     *       NOTHING}. Exactly one transaction can claim an eventId; a duplicate delivery —
     *       sequential or concurrent, racing hard or not — observes 0 inserted rows and
     *       returns as a no-op. The claim is never the result of a separate, earlier
     *       commit: if the payment/outbox work fails, the whole transaction rolls back and
     *       the claim goes with it so Kafka can redeliver.
     *   <li>{@code payments} is claimed with {@code INSERT ... ON CONFLICT (reservation_id)
     *       DO NOTHING}. Payment is one-per-reservation, so a second ReservationCreated for
     *       a reservation that already has a payment — even bearing a different eventId — is
     *       a deterministic no-op instead of a database uniqueness exception that would be
     *       retried and dead-lettered.
     * </ol>
     *
     * Neither path raises an integrity exception on a duplicate, so an idempotency race is
     * never classified as a poison message.
     */
    @Override
    @Transactional
    public void process(EventEnvelope<JsonNode> envelope, String correlationId) {
        // A blank event type is an unclassifiable, malformed envelope -> poison path.
        String eventType = envelope.eventType();
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("envelope.eventType must be set, got: " + envelope);
        }
        // Everything else on reservation.events.v1 is deliberately ignored, unprocessed.
        if (!RESERVATION_CREATED.equals(eventType)) {
            log.debug("Ignoring {} on reservation.events.v1", eventType);
            return;
        }

        if (envelope.eventId() == null) {
            throw new IllegalArgumentException("envelope.eventId must be set for " + eventType);
        }
        if (envelope.payload() == null) {
            throw new IllegalArgumentException("envelope.payload must be set for " + eventType);
        }
        ReservationCreatedPayload created = parseCreated(envelope.payload());
        validateCreated(created);

        // Idempotency claim (ADR 004): 1 = this transaction won the event, 0 = duplicate.
        if (processedEvents.tryClaim(envelope.eventId()) == 0) {
            log.debug("Skipping duplicate delivery of event {}", envelope.eventId());
            return;
        }

        PaymentStatus outcome = decideOutcome(created.amountCents());

        // One payment per reservation: another event for an already-paid reservation is a
        // no-op here (the processed_events claim above still records the event as handled).
        int claimedPayments =
                payments.tryClaim(created.reservationId(), created.amountCents(), outcome.name(), Instant.now());
        if (claimedPayments == 0) {
            log.warn(
                    "Ignoring ReservationCreated for reservation {} which already has a payment; "
                            + "event {} recorded as processed",
                    created.reservationId(), envelope.eventId());
            return;
        }

        PaymentEntity payment = payments.findByReservationId(created.reservationId()).orElseThrow(
                () -> new IllegalStateException(
                        "Claimed payment row for reservation " + created.reservationId() + " not found"));

        outbox.save(new OutboxEventEntity(
                UUID.randomUUID(),
                "Payment",
                created.reservationId(),
                payment.getStatus() == PaymentStatus.SUCCEEDED ? PAYMENT_SUCCEEDED : PAYMENT_FAILED,
                serializeOutcome(payment),
                correlationId));
    }

    private ReservationCreatedPayload parseCreated(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ReservationCreatedPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize ReservationCreated payload", ex);
        }
    }

    private void validateCreated(ReservationCreatedPayload created) {
        if (created.reservationId() == null || created.reservationId() <= 0) {
            throw new IllegalArgumentException(
                    "ReservationCreated reservationId must be a positive id: " + created.reservationId());
        }
        if (created.customerId() == null || created.customerId() <= 0) {
            throw new IllegalArgumentException(
                    "ReservationCreated customerId must be a positive id: " + created.customerId());
        }
        if (created.eventId() == null || created.eventId() <= 0) {
            throw new IllegalArgumentException(
                    "ReservationCreated eventId must be a positive id: " + created.eventId());
        }
        if (created.seatIds() == null || created.seatIds().isEmpty()) {
            throw new IllegalArgumentException("ReservationCreated seatIds must be non-empty");
        }
        if (created.seatIds().contains(null) || created.seatIds().stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException(
                    "ReservationCreated seatIds must contain positive ids: " + created.seatIds());
        }
        // amountCents is the sum of non-negative seat prices upstream; a missing value
        // deserializes to null (boxed type) and a negative value is a producer bug.
        if (created.amountCents() == null || created.amountCents() < 0) {
            throw new IllegalArgumentException(
                    "ReservationCreated amountCents must be non-negative: " + created.amountCents());
        }
    }

    private PaymentStatus decideOutcome(int amountCents) {
        return amountCents >= properties.getDeclineThresholdCents() ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
    }

    private String serializeOutcome(PaymentEntity payment) {
        try {
            return objectMapper.writeValueAsString(new PaymentProcessedPayload(
                    payment.getId(), payment.getReservationId(), payment.getAmountCents(), payment.getStatus()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize payment outcome payload", ex);
        }
    }

    /** Wire payload of a {@code reservation.ReservationCreated} event (reservation-service contract). */
    public record ReservationCreatedPayload(
            Long reservationId, Long customerId, Long eventId, List<Long> seatIds, Integer amountCents) {}

    /** Wire payload of a {@code payment.PaymentSucceeded} / {@code payment.PaymentFailed} event. */
    public record PaymentProcessedPayload(long paymentId, long reservationId, int amountCents, PaymentStatus status) {}
}
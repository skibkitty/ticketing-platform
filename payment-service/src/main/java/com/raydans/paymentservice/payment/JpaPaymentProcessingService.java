package com.raydans.paymentservice.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.paymentservice.outbox.OutboxEventEntity;
import com.raydans.paymentservice.outbox.OutboxEventRepository;
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

    private final PaymentRepository payments;
    private final ProcessedEventRepository processedEvents;
    private final OutboxEventRepository outbox;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    JpaPaymentProcessingService(
            PaymentRepository payments,
            ProcessedEventRepository processedEvents,
            OutboxEventRepository outbox,
            PaymentProperties properties,
            ObjectMapper objectMapper) {
        this.payments = payments;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void process(EventEnvelope<JsonNode> envelope, String correlationId) {
        // Filter by type: only ReservationCreated drives a Payment (others on the topic are ignored).
        if (!RESERVATION_CREATED.equals(envelope.eventType())) {
            log.debug("Ignoring {} on reservation.events.v1", envelope.eventType());
            return;
        }
        // Idempotency (ADR 004): a previously processed eventId is a no-op.
        if (processedEvents.existsById(envelope.eventId())) {
            log.debug("Skipping duplicate delivery of event {}", envelope.eventId());
            return;
        }

        ReservationCreatedPayload created;
        try {
            created = objectMapper.treeToValue(envelope.payload(), ReservationCreatedPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize ReservationCreated payload", ex);
        }

        PaymentEntity payment = payments.saveAndFlush(
                new PaymentEntity(created.reservationId(), created.amountCents(), PaymentStatus.PENDING));
        payment.settle(decideOutcome(created.amountCents()));

        outbox.save(new OutboxEventEntity(
                UUID.randomUUID(),
                "Payment",
                created.reservationId(),
                payment.getStatus() == PaymentStatus.SUCCEEDED ? PAYMENT_SUCCEEDED : PAYMENT_FAILED,
                serializeOutcome(payment),
                correlationId));

        // Effect and processed-row insert share one transaction (ADR 004): a rollback drops both,
        // so a redelivered event never double-applies an effect.
        processedEvents.save(new ProcessedEventEntity(envelope.eventId()));
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
            long reservationId, long customerId, long eventId, List<Long> seatIds, int amountCents) {}

    /** Wire payload of a {@code payment.PaymentSucceeded} / {@code payment.PaymentFailed} event. */
    public record PaymentProcessedPayload(long paymentId, long reservationId, int amountCents, PaymentStatus status) {}
}

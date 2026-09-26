package com.raydans.notificationservice.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(JpaNotificationService.class);

    private final ProcessedEventRepository processedEvents;
    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;
    private final int maxPageSize;

    JpaNotificationService(
            ProcessedEventRepository processedEvents,
            NotificationRepository notifications,
            ObjectMapper objectMapper,
            @Value("${app.notifications.max-page-size:200}") int maxPageSize) {
        this.processedEvents = processedEvents;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.maxPageSize = maxPageSize;
    }

    /**
     * Records a Notification for a terminal reservation event idempotently.
     *
     * <p>Two atomic database claims stand between a delivered event and a Customer
     * being told about their Reservation twice (ADR 004):
     *
     * <ol>
     *   <li>{@code processed_events} is claimed with {@code INSERT ... ON CONFLICT DO
     *       NOTHING}. Exactly one transaction can claim an eventId, so a duplicate
     *       delivery — sequential or concurrent, racing hard or not — observes 0
     *       inserted rows and returns as a no-op. The claim is never the result of a
     *       separate, earlier commit: if the Notification work fails the whole
     *       transaction rolls back and the claim goes with it, so Kafka can redeliver.
     *   <li>{@code notifications} is claimed per (Reservation, terminal type) the same
     *       way, so a differently ID'd repeat of a transition this Customer has
     *       already been told about is a deterministic no-op rather than a second
     *       message.
     * </ol>
     *
     * <p>Neither path raises an integrity exception on a duplicate, so an idempotency
     * race is never classified as a poison message.
     */
    @Override
    @Transactional
    public void record(EventEnvelope<JsonNode> envelope) {
        // A blank event type is an unclassifiable, malformed envelope -> poison path.
        String eventType = envelope.eventType();
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("envelope.eventType must be set, got: " + envelope);
        }
        // Everything else on the shared reservation.events.v1 topic (notably
        // reservation.ReservationCreated) is another service's business: deliberately
        // ignored, not claimed, not dead-lettered.
        NotificationType type = NotificationType.fromEventType(eventType);
        if (type == null) {
            log.debug("Ignoring {} on reservation.events.v1", eventType);
            return;
        }
        if (envelope.eventId() == null) {
            throw new IllegalArgumentException("envelope.eventId must be set for " + eventType);
        }
        if (envelope.payload() == null) {
            throw new IllegalArgumentException("envelope.payload must be set for " + eventType);
        }
        ReservationTerminalPayload terminal = parseTerminal(envelope.payload());
        validate(terminal);

        // Idempotency claim (ADR 004): 1 = this transaction won the event, 0 = duplicate.
        if (processedEvents.tryClaim(envelope.eventId()) == 0) {
            log.debug("Skipping duplicate delivery of event {}", envelope.eventId());
            return;
        }

        String message = type.render(terminal.reservationId(), terminal.seatIds());
        int claimed = notifications.tryClaim(
                terminal.customerId(), terminal.reservationId(), type.name(), payloadJson(message));
        if (claimed == 0) {
            // The event is genuinely new, it just says something the Customer has already
            // been told. It stays claimed so it is not re-examined on every redelivery.
            log.warn(
                    "Ignoring {} for reservation {} which has already been notified (customer {}); "
                            + "event {} recorded as processed",
                    eventType, terminal.reservationId(), terminal.customerId(), envelope.eventId());
            return;
        }

        // The simulated send. The row is committed when this transaction commits, so a
        // rollback after this line would mean the event is redelivered and logged again:
        // the log is at-least-once, exactly like the outbox that produced the event.
        log.info(
                "Simulated send: {} notification for reservation {} to customer {} — {}",
                type, terminal.reservationId(), terminal.customerId(), message);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> listForCustomer(long customerId, int limit, String cursor) {
        if (customerId <= 0) {
            throw new IllegalArgumentException("customerId must be a positive id: " + customerId);
        }
        List<NotificationEntity> page = cursor == null || cursor.isBlank()
                ? notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(
                        customerId, PageRequest.of(0, clampedLimit(limit)))
                : pageAfterCursor(customerId, cursor, clampedLimit(limit));
        return page.stream()
                .map(row -> NotificationResponse.from(row, messageOf(row)))
                .toList();
    }

    /**
     * A request may ask for any page size it likes; the query never runs at more than the
     * configured maximum. Clamping rather than rejecting keeps a client that hard-codes a
     * large limit working, while still bounding the query.
     */
    private int clampedLimit(int limit) {
        return Math.min(Math.max(limit, 1), maxPageSize);
    }

    private List<NotificationEntity> pageAfterCursor(long customerId, String cursor, int limit) {
        NotificationCursor position = NotificationCursor.decode(cursor);
        return notifications.findPageAfter(customerId, position.sentAt(), position.id(), limit);
    }

    @Override
    public String cursorAfter(NotificationResponse response) {
        return NotificationCursor.of(response.sentAt(), response.id()).encode();
    }

    private ReservationTerminalPayload parseTerminal(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ReservationTerminalPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize reservation terminal payload", ex);
        }
    }

    /**
     * Rejects a terminal reservation event this service could not turn into a Notification.
     *
     * <p>Only the fields the Notification is actually built from are checked. {@code
     * eventId} and {@code amountCents} ride along on the upstream contract but nothing
     * here reads them, so a bad value in either is a producer bug and not a reason to
     * quarantine: denying a Customer the one message that explains their Hold lapsing
     * over a field this message does not mention would be the wrong trade.
     */
    private void validate(ReservationTerminalPayload terminal) {
        if (terminal.reservationId() == null || terminal.reservationId() <= 0) {
            throw new IllegalArgumentException(
                    "terminal reservation event reservationId must be a positive id: " + terminal.reservationId());
        }
        // The recipient is the whole point of this service: a terminal event that
        // cannot say who to tell is undeliverable, not merely incomplete.
        if (terminal.customerId() == null || terminal.customerId() <= 0) {
            throw new IllegalArgumentException(
                    "terminal reservation event customerId must be a positive id: " + terminal.customerId());
        }
        // seatIds may legitimately be EMPTY: the expiry path publishes the seats a
        // transition actually released, which is none when a hold was re-extended
        // before the sweep saw it. Only the ids that are there have to be real.
        if (terminal.seatIds() == null) {
            throw new IllegalArgumentException("terminal reservation event seatIds must be present (possibly empty)");
        }
        if (terminal.seatIds().contains(null) || terminal.seatIds().stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException(
                    "terminal reservation event seatIds must contain positive ids: " + terminal.seatIds());
        }
    }

    private String payloadJson(String message) {
        try {
            return objectMapper.writeValueAsString(new NotificationPayload(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize notification payload", ex);
        }
    }

    /**
     * The stored text a Customer is shown. Only this service writes payloads, so an
     * unreadable one is a legacy or corrupt row rather than bad input: fall back to
     * the type's own wording instead of failing the Customer's whole inbox over it.
     */
    private String messageOf(NotificationEntity row) {
        String stored = storedMessage(row);
        if (stored != null) {
            return stored;
        }
        log.warn(
                "No readable message in the payload of notification {} (reservation {}, type {}); "
                        + "falling back to the type's own wording",
                row.getId(), row.getReservationId(), row.getType());
        return row.getType().defaultMessage(row.getReservationId());
    }

    private String storedMessage(NotificationEntity row) {
        String payload = row.getPayload();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode message = objectMapper.readTree(payload).get("message");
            return message != null && message.isTextual() && !message.asText().isBlank() ? message.asText() : null;
        } catch (JsonProcessingException ex) {
            log.debug("Payload of notification {} is not readable JSON", row.getId(), ex);
            return null;
        }
    }

    /** What is persisted as a Notification's {@code payload}. */
    public record NotificationPayload(String message) {}

    /**
     * Wire payload of {@code reservation.ReservationConfirmed} /
     * {@code reservation.ReservationCancelled} / {@code reservation.ReservationExpired}
     * (reservation-service contract — the three records are identical field for field).
     *
     * <p>{@code eventId} is the Event the Reservation is for — NOT the Kafka
     * message id, which is {@code envelope.eventId()} and the only idempotency key.
     */
    public record ReservationTerminalPayload(
            Long reservationId, Long customerId, Long eventId, List<Long> seatIds, Integer amountCents) {}
}

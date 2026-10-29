package com.raydans.notificationservice.notification;

import java.util.List;

/**
 * What a Notification says, one value per terminal Reservation state, plus the
 * Refund of a Payment that settled after its Reservation expired (#52).
 *
 * <p>The vocabulary is the Reservation's own (CONTEXT.md): a Reservation reaches
 * exactly one of {@code CONFIRMED}, {@code CANCELLED} or {@code EXPIRED} and
 * each of those notifies once. {@code PENDING_PAYMENT} is deliberately absent —
 * nothing is sent while a Hold is still running. The values are the terminal
 * <em>event</em> names so the mapping between a Kafka event and a stored type is
 * total and checkable, with no second spelling to keep in sync.
 */
public enum NotificationType {
    RESERVATION_CONFIRMED("reservation.ReservationConfirmed"),
    RESERVATION_CANCELLED("reservation.ReservationCancelled"),
    RESERVATION_EXPIRED("reservation.ReservationExpired"),
    PAYMENT_REFUNDED("payment.PaymentRefunded");

    private final String eventType;

    NotificationType(String eventType) {
        this.eventType = eventType;
    }

    /** The event type this Notification is recorded for, on whichever topic carries it. */
    public String eventType() {
        return eventType;
    }

    /**
     * The Notification type for an event, or {@code null} for anything else on
     * the topics. Returning null rather than throwing is deliberate: both topics
     * are shared, so the uninteresting types on them ({@code
     * reservation.ReservationCreated}, the payment outcomes) are a normal part of
     * the stream and must be ignored, not treated as malformed.
     */
    public static NotificationType fromEventType(String eventType) {
        for (NotificationType type : values()) {
            if (type.eventType.equals(eventType)) {
                return type;
            }
        }
        return null;
    }

    /**
     * The customer-facing text for a recorded Notification. The seat sentence is
     * appended only when the event actually named seats: the expiry path publishes
     * a legitimately empty {@code seatIds} when a hold was re-extended before the
     * sweep saw it, and "your seats" with nothing after it would be nonsense.
     */
    public String render(long reservationId, List<Long> seatIds) {
        String headline = defaultMessage(reservationId);
        if (seatIds == null || seatIds.isEmpty()) {
            return headline;
        }
        boolean single = seatIds.size() == 1;
        String seats = (single ? "Seat " : "Seats ") + String.join(", ", seatIds.stream().map(String::valueOf).toList());
        return switch (this) {
            case RESERVATION_CONFIRMED -> headline + " " + seats + (single ? " is yours." : " are yours.");
            case RESERVATION_CANCELLED, RESERVATION_EXPIRED ->
                    headline + " " + seats + (single ? " has been released." : " have been released.");
            // A refund names no Seats: they went with the expiry it follows.
            case PAYMENT_REFUNDED -> headline;
        };
    }

    /**
     * This type's wording with no seat detail — the message used when a stored
     * payload carries no readable text. Deriving it from the type keeps the
     * fallback a pure function, so one unreadable row can never fail a whole inbox.
     */
    public String defaultMessage(long reservationId) {
        return switch (this) {
            case RESERVATION_CONFIRMED -> "Your reservation " + reservationId + " is confirmed.";
            case RESERVATION_CANCELLED ->
                    "Your reservation " + reservationId + " was cancelled because the payment was declined.";
            case RESERVATION_EXPIRED ->
                    "Your reservation " + reservationId + " expired before payment completed.";
            case PAYMENT_REFUNDED ->
                    "Your payment for reservation " + reservationId
                            + " was refunded because it arrived after the reservation expired.";
        };
    }
}

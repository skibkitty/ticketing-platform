package com.raydans.notificationservice.notification;

import java.util.List;

/**
 * What a Notification says, one value per terminal Reservation state.
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
    RESERVATION_EXPIRED("reservation.ReservationExpired");

    private final String eventType;

    NotificationType(String eventType) {
        this.eventType = eventType;
    }

    /** The {@code reservation.events.v1} event type this Notification is recorded for. */
    public String eventType() {
        return eventType;
    }

    /**
     * The Notification type for a terminal reservation event, or {@code null} for
     * anything else on the topic. Returning null rather than throwing is
     * deliberate: {@code reservation.events.v1} is shared, so the uninteresting
     * types on it ({@code reservation.ReservationCreated}) are a normal part of
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
        };
    }
}

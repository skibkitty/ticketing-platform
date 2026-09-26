package com.raydans.notificationservice.notification;

import java.time.Instant;

/**
 * One Notification as the Customer sees it. The {@code message} is the text that was
 * actually sent (from the stored payload), not one re-rendered per read, so what is
 * listed is what was said.
 *
 * <p>Lives beside the service rather than in {@code web} because it is what the service
 * returns, not a rendering of it: the service layer should not depend on the transport.
 */
public record NotificationResponse(
        long id,
        long reservationId,
        long recipientCustomerId,
        NotificationType type,
        String message,
        Instant sentAt) {

    public static NotificationResponse from(NotificationEntity row, String message) {
        return new NotificationResponse(
                row.getId(),
                row.getReservationId(),
                row.getRecipientCustomerId(),
                row.getType(),
                message,
                row.getSentAt());
    }
}

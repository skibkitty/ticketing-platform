package com.raydans.notificationservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One Notification recorded for one Customer about one Reservation.
 *
 * <p>Rows are written exclusively by {@link NotificationRepository#tryClaim}, whose
 * {@code ON CONFLICT DO NOTHING} is the atomic claim that keeps a duplicate from
 * becoming a second row (and a second send). There are deliberately no setters: this
 * type is populated by Hibernate on the way out, never by application code on the way
 * in. (The repository still inherits {@code save}/{@code delete} from
 * {@code JpaRepository}; nothing calls them, and nothing should.)
 */
@Entity
@Table(schema = "notification", name = "notifications")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_customer_id", nullable = false)
    private long recipientCustomerId;

    @Column(name = "reservation_id", nullable = false)
    private long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    // DB-owned (DEFAULT now()), never set or updated from JPA: insertable/updatable false.
    // Written by the claim at the moment the send is simulated, so it is the send time.
    @Column(name = "sent_at", nullable = false, updatable = false, insertable = false)
    private Instant sentAt;

    protected NotificationEntity() {}

    public Long getId() {
        return id;
    }

    public long getRecipientCustomerId() {
        return recipientCustomerId;
    }

    public long getReservationId() {
        return reservationId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}

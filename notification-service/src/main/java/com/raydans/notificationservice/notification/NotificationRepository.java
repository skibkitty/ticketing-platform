package com.raydans.notificationservice.notification;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * Atomically claims the one Notification a Reservation owes for a given terminal
     * type — the same one-per-aggregate claim payment-service makes for its Payment
     * (ADR 004). Returns 1 when this transaction wrote the row, 0 when the
     * Notification already exists.
     *
     * <p>The second guard is not redundant with {@code processed_events}. Dedupe on
     * {@code eventId} only stops the <em>same</em> event arriving twice; a differently
     * ID'd repeat of the same transition would still get past it, and here that
     * duplicate means the Customer is told twice about one Reservation. Because the
     * conflict is resolved by the database, a repeat is a successful no-op rather than
     * an integrity exception that would be retried and dead-lettered.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO notification.notifications "
                            + "(recipient_customer_id, reservation_id, type, payload) "
                            + "VALUES (:recipientCustomerId, :reservationId, :type, CAST(:payload AS jsonb)) "
                            + "ON CONFLICT (reservation_id, type) DO NOTHING",
            nativeQuery = true)
    int tryClaim(
            @Param("recipientCustomerId") long recipientCustomerId,
            @Param("reservationId") long reservationId,
            @Param("type") String type,
            @Param("payload") String payload);

    /**
     * A Customer's Notifications, newest first — the order a notification inbox is read in.
     *
     * <p>Paged by the database ({@code Pageable} carries the limit), so the size of a
     * Customer's history can never become the size of a query.
     */
    List<NotificationEntity> findByRecipientCustomerIdOrderBySentAtDescIdDesc(
            long recipientCustomerId, Pageable pageable);

    /**
     * The next page after a cursor, using keyset pagination on the same
     * {@code (sent_at DESC, id DESC)} order the inbox is read in.
     *
     * <p>Keyset rather than an offset: new Notifications arrive at the head of this order
     * constantly, so an offset page would silently shift rows between requests and a client
     * paging through history could read one row twice or skip one. The {@code id} tiebreak
     * is not optional — {@code sent_at} is a database timestamp and a burst of inserts
     * shares one, so {@code sent_at} alone is not a unique position.
     *
     * <p>Strictly less-than, so a page never repeats the cursor row itself.
     */
    @Query(
            value =
                    "SELECT * FROM notification.notifications "
                            + "WHERE recipient_customer_id = :recipientCustomerId "
                            + "AND (sent_at < :beforeSentAt "
                            + "     OR (sent_at = :beforeSentAt AND id < :beforeId)) "
                            + "ORDER BY sent_at DESC, id DESC "
                            + "LIMIT :limit",
            nativeQuery = true)
    List<NotificationEntity> findPageAfter(
            @Param("recipientCustomerId") long recipientCustomerId,
            @Param("beforeSentAt") Instant beforeSentAt,
            @Param("beforeId") long beforeId,
            @Param("limit") int limit);
}

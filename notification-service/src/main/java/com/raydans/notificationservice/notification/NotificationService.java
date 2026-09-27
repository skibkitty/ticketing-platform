package com.raydans.notificationservice.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.raydans.common.event.EventEnvelope;

/**
 * What the service does, independent of Kafka or HTTP. The inbound half records a
 * Notification for a terminal reservation event; the outbound half is the read
 * surface a Customer sees.
 */
public interface NotificationService {

    /**
     * Records the Notification a terminal reservation event owes its Customer, once.
     *
     * @throws IllegalArgumentException if a supported event carries a payload that is
     *                                  malformed for it (the record is then retried and
     *                                  dead-lettered, ADR 008)
     */
    void record(EventEnvelope<JsonNode> envelope);

    /**
     * One page of a Customer's Notifications, newest first, with the position to resume
     * from when there is one. The page is empty when they have none.
     *
     * <p>Paged rather than complete: an inbox grows without bound, so returning all of it
     * would make one request's cost a function of how long the Customer has existed. The
     * page size is clamped to the service's maximum, so a caller cannot ask past it.
     *
     * <p>The page carries its own {@link NotificationPage#nextCursor()}, because a cursor
     * only makes a page reachable if the caller is given it. {@code null} there means there
     * is nothing after this page.
     *
     * @param limit  how many Notifications to return at most; values above the service's
     *               maximum are clamped, not rejected
     * @param cursor an opaque position from a previous page, or {@code null} for the newest
     *               page. Keyset, not an offset: new Notifications arriving at the head of
     *               the order must not shift rows out from under a client paging through
     *               history.
     * @throws IllegalArgumentException if {@code customerId} is not a positive id, or the
     *                                  cursor is not one this service issued — the same
     *                                  id rule {@link #record} holds inbound payloads to,
     *                                  enforced here rather than on the handler argument so
     *                                  it does not depend on which validation mechanism
     *                                  Spring is configured with
     */
    NotificationPage listForCustomer(long customerId, int limit, String cursor);

    /**
     * The cursor for the row after {@code response} in a Customer's inbox: the position a
     * caller resumes from once it has read that row.
     *
     * <p>Always a position, never {@code null} — a position says where a row is, and says
     * nothing about whether anything follows it. Whether there is a next page at all is
     * {@link NotificationPage#nextCursor()}'s answer, and it can only come from the query
     * that read the page.
     *
     * <p>It is a position, not a grant: the row is looked up within the {@code customerId}
     * given in the same request, so a cursor issued for one Customer can only ever page an
     * empty inbox for another.
     */
    String cursorAfter(NotificationResponse response);
}

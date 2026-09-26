package com.raydans.notificationservice.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.raydans.common.event.EventEnvelope;
import java.util.List;

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
     * One page of a Customer's Notifications, newest first. Empty when they have none.
     *
     * <p>Paged rather than complete: an inbox grows without bound, so returning all of it
     * would make one request's cost a function of how long the Customer has existed. The
     * page size is clamped to the service's maximum, so a caller cannot ask past it.
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
    List<NotificationResponse> listForCustomer(long customerId, int limit, String cursor);

    /**
     * The cursor for the row after {@code response} in a Customer's inbox, or {@code null}
     * when {@code response} is the oldest row returned, so there is nothing further to read.
     *
     * <p>Exposed so the position a client pages from is defined in one place rather than
     * re-derived from the wire format by each caller.
     */
    String cursorAfter(NotificationResponse response);
}

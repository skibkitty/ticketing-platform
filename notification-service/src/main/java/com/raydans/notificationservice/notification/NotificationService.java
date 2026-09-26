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
     * A Customer's Notifications, newest first. Empty when they have none.
     *
     * @throws IllegalArgumentException if {@code customerId} is not a positive id — the
     *                                  same rule {@link #record} holds inbound payloads to,
     *                                  enforced here rather than on the handler argument so
     *                                  it does not depend on which validation mechanism
     *                                  Spring is configured with
     */
    List<NotificationResponse> listForCustomer(long customerId);
}

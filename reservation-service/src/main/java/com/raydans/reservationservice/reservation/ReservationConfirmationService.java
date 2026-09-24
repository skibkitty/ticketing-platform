package com.raydans.reservationservice.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.raydans.common.event.EventEnvelope;

public interface ReservationConfirmationService {

    /**
     * Applies a {@code payment.PaymentSucceeded} outcome to a Reservation idempotently.
     *
     * <p>The ADR 007 guard is enforced here: the outcome is applied <em>only while the
     * Reservation is {@code PENDING_PAYMENT}</em>; otherwise the event is recorded as
     * processed and no state changes (a late outcome must never re-flip seats a reservation
     * no longer owns).
     */
    void process(EventEnvelope<JsonNode> envelope, String correlationId);
}
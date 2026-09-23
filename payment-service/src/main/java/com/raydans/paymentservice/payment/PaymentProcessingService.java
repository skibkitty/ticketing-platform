package com.raydans.paymentservice.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.raydans.common.event.EventEnvelope;

public interface PaymentProcessingService {

    /** Applies a delivered saga event to the Payment, idempotently (ADR 004). */
    void process(EventEnvelope<JsonNode> envelope, String correlationId);
}

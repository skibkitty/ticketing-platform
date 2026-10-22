package com.raydans.paymentservice.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaPaymentQueryService implements PaymentQueryService {

    private final PaymentRepository payments;

    JpaPaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse get(long reservationId, long customerId) {
        // Ids are issued from a sequence and are positive, so anything else is a
        // malformed request rather than an absent Payment: 400, not 404.
        if (reservationId <= 0) {
            throw new InvalidPaymentLookupException("reservationId must be a positive id");
        }
        // The gateway only ever publishes a positive Customer id; anything else did
        // not come from it.
        if (customerId <= 0) {
            throw new InvalidPaymentLookupException("X-Customer-Id must be a positive id");
        }
        // "No such payment" and "not yours" are the same exception. The detail is for
        // logs only; the response says "Payment not found" for both (ADR 016).
        return payments.findByReservationIdAndCustomerId(reservationId, customerId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment for reservation " + reservationId + " was not found"));
    }
}

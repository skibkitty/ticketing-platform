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
        // Same message for "no such payment" and "not yours", so a caller cannot probe
        // which reservation ids have been paid for (ADR 014, ADR 016).
        return payments.findByReservationIdAndCustomerId(reservationId, customerId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment for reservation " + reservationId + " was not found"));
    }
}

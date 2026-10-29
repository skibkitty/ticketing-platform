package com.raydans.paymentservice.payment;

import java.time.Instant;

/**
 * One Payment as its Customer sees it: the money movement behind a Reservation.
 *
 * <p>Lives beside the service rather than in {@code web} because it is what the service
 * returns, not a rendering of it: the service layer should not depend on the transport.
 */
public record PaymentResponse(
        long id,
        long reservationId,
        int amountCents,
        PaymentStatus status,
        Instant createdAt,
        Instant settledAt,
        Instant refundedAt) {

    public static PaymentResponse from(PaymentEntity row) {
        return new PaymentResponse(
                row.getId(),
                row.getReservationId(),
                row.getAmountCents(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getSettledAt(),
                row.getRefundedAt());
    }
}

package com.raydans.paymentservice.payment;

public interface PaymentQueryService {

    /**
     * The Payment behind {@code reservationId}, if it belongs to {@code customerId}.
     *
     * <p>The Customer is a required argument rather than a check the web layer makes, so
     * the ownership rule is not something a second caller of this method can leave out
     * (ADR 016).
     *
     * @throws PaymentNotFoundException when there is no Payment for the reservation, or
     *     there is one and it belongs to someone else — deliberately indistinguishable
     * @throws InvalidPaymentLookupException when either id is not positive
     */
    PaymentResponse get(long reservationId, long customerId);
}

package com.raydans.paymentservice.web;

import com.raydans.paymentservice.payment.PaymentQueryService;
import com.raydans.paymentservice.payment.PaymentResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    /**
     * The header the gateway writes the verified caller's {@code Customer.id} into
     * (ADR 002), and the only thing this read is scoped by.
     */
    public static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final PaymentQueryService payments;

    public PaymentController(PaymentQueryService payments) {
        this.payments = payments;
    }

    /**
     * The outcome of the calling Customer's payment for one of their Reservations.
     *
     * <p>Keyed by reservation id, because that is the id a Customer holds: the Payment's
     * own id only ever appears on the saga's events. But the reservation id names the
     * row and does not authorize reading it — the Customer travels with the lookup, so
     * someone else's reservation id is answered exactly as an unissued one is: 404.
     * ADR 016 records why this follows ADR 014 rather than the spec's unscoped wording.
     *
     * <p>The header is required, which also makes this route unreachable by anything that
     * did not come through the gateway.
     */
    @GetMapping("/api/v1/payments/{reservationId}")
    PaymentResponse get(
            @PathVariable("reservationId") long reservationId,
            @RequestHeader(value = CUSTOMER_HEADER, required = true) long customerId) {
        return payments.get(reservationId, customerId);
    }
}

package com.raydans.paymentservice.payment;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** A {@code SUCCEEDED} Payment whose money was returned because it settled too late (#52). */
    REFUNDED
}

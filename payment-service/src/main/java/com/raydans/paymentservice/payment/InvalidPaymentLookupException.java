package com.raydans.paymentservice.payment;

/**
 * A status read the service refuses before any lookup, because what it was asked
 * for cannot name a Payment. The message is written for the caller and is the only
 * exception text this service returns in a response body.
 */
public class InvalidPaymentLookupException extends RuntimeException {

    public InvalidPaymentLookupException(String message) {
        super(message);
    }
}

package com.raydans.apigateway.auth;

/**
 * A token the gateway will not accept: malformed, signed by someone else,
 * expired, or carrying a {@code roles} claim it cannot read.
 *
 * <p>One type for all of them, carrying no detail outward. The distinctions are
 * real to whoever debugs a login and none are the caller's business, and
 * "that signature is wrong" is a hint about which bytes to change.
 */
public class InvalidTokenException extends RuntimeException {

    InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.raydans.apigateway.auth;

/**
 * A token the gateway will not accept: malformed, signed by someone else,
 * expired, or carrying a {@code roles} claim it cannot read.
 *
 * <p>One type for all of them, carrying no detail. The distinctions are real to
 * whoever debugs a login and none of them are the caller's business — telling a
 * client "your token expired" is the difference between a useful refresh and a
 * support conversation, and telling it "that signature is wrong" is a hint about
 * which bytes to change.
 */
public class InvalidTokenException extends RuntimeException {

    InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}

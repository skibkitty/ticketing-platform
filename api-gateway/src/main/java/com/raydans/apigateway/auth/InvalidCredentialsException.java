package com.raydans.apigateway.auth;

/**
 * Credentials the gateway will not issue a token for. Deliberately not saying
 * which half was wrong: a login surface that distinguishes "no such user" from
 * "wrong password" is an oracle for which usernames exist.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}

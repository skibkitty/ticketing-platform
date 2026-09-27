package com.raydans.apigateway.auth;

/**
 * Credentials the gateway will not issue a token for.
 *
 * <p>Deliberately not saying which half was wrong. The directory's contract
 * already makes "no such user" and "wrong password" the same answer, and this
 * keeps it that way: a login surface that distinguishes them is an oracle for
 * which usernames exist, and usernames are the half of a credential an attacker
 * can guess.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}

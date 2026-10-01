package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * A known caller: the credential the gateway exchanges for a token, and the
 * identity that token will then speak for.
 *
 * <p>The password is a plaintext comparison value on purpose. There is no
 * password store to hash it for, and hashing a demo credential would be a claim
 * about security the thing holding it does not deserve.
 *
 * @param username the login name, which is what a caller types and never what
 *     a downstream service is told (ADR 002)
 * @param callerId this login's own immutable id, and the token's subject. It is
 *     a {@code Customer.id} only for a caller that also holds
 *     {@link Role#CUSTOMER}; an organizer's is the organizer's, and is never
 *     published as a Customer (CONTEXT.md)
 * @param password the credential checked at login
 * @param roles    the roles granted to a caller who authenticates as this name
 */
public record CallerCredentials(String username, long callerId, String password, Set<Role> roles) {

    public CallerCredentials {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}

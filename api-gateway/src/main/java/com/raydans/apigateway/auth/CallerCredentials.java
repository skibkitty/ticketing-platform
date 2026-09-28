package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * A known caller: the credential the gateway exchanges for a token, and the
 * roles that token carries.
 *
 * <p>The password is a plaintext comparison value on purpose. There is no
 * password store to hash it for, and hashing a demo credential would be a claim
 * about security the thing holding it does not deserve.
 *
 * @param username the login name, and the token's subject
 * @param password the credential checked at login
 * @param roles    the roles granted to a caller who authenticates as this name
 */
public record CallerCredentials(String username, String password, Set<Role> roles) {

    public CallerCredentials {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}

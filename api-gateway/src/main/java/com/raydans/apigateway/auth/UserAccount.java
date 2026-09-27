package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * A known caller: the credential the gateway will exchange for a token, and the
 * roles that token will carry.
 *
 * <p>The password is a plaintext comparison value on purpose. There is no
 * password store to hash it for — a real identity provider (out of scope) would
 * be the thing to ask for a hash from, and putting a hash in front of a demo
 * credential would be a claim about security that the thing holding the
 * credentials does not deserve.
 *
 * @param username the login name, and the token's subject
 * @param password the credential checked at login
 * @param roles    the roles granted to a caller who authenticates as this user
 */
public record UserAccount(String username, String password, Set<Role> roles) {

    public UserAccount {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}

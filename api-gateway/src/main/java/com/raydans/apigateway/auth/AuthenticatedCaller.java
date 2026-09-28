package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * The caller the gateway has authenticated for the current request: what the
 * gateway replaces a token with, and the whole of the platform's knowledge of
 * who is asking (ADR 002).
 *
 * @param username the token's subject, never a caller-supplied id
 * @param roles    the roles the token was signed with
 */
public record AuthenticatedCaller(String username, Set<Role> roles) {

    public AuthenticatedCaller {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * @return whether this caller holds at least one of {@code acceptable} —
     *     any one, since a rule naming roles lists the alternatives that grant
     *     the action rather than requirements to satisfy together
     */
    public boolean holdsAny(Set<Role> acceptable) {
        return roles.stream().anyMatch(acceptable::contains);
    }
}

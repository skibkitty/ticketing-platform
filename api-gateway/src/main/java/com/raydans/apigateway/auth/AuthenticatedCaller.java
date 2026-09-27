package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * The caller the gateway has authenticated for the current request.
 *
 * <p>This is what the gateway replaces a token with. Everything downstream of
 * the auth filter reads the roles from here and never re-parses the token
 * (ADR 002), so this record is the whole of the platform's knowledge of who is
 * asking.
 *
 * @param username the token's subject — the authenticated user, never a
 *     caller-supplied id
 * @param roles    the roles the token was signed with
 */
public record AuthenticatedCaller(String username, Set<Role> roles) {

    public AuthenticatedCaller {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * @return whether this caller holds at least one of {@code acceptable}.
     *     Any one, not all: a rule that names roles lists the alternatives that
     *     grant the action, so a caller needs one of them. Requiring all of them
     *     would mean the roles a rule lists must be held simultaneously, which
     *     would make a rule naming two roles reachable only by a caller holding
     *     both — so an organizer could not create an Event and an admin who was
     *     not also an organizer could not either.
     */
    public boolean holdsAny(Set<Role> acceptable) {
        return roles.stream().anyMatch(acceptable::contains);
    }
}

package com.raydans.apigateway.auth;

import java.util.Set;

/**
 * The caller the gateway has authenticated for the current request: what the
 * gateway replaces a token with, and the whole of the platform's knowledge of
 * who is asking (ADR 002).
 *
 * <p>The two components answer different questions and are not interchangeable.
 * {@code callerId} is which login this is — the subject, and the id of whatever
 * that login belongs to. {@link #isCustomer()} is whether that login is
 * <em>a</em> Customer, and only then is {@code callerId} a {@code Customer.id}
 * and only then may a downstream service be told it. An organizer or an admin is
 * a caller like any other and has an id of its own, but holding those roles does
 * not make it a Customer: the domain gives them no Customer identity to hold
 * (CONTEXT.md), so nothing downstream may be told they have one.
 *
 * @param callerId the token's subject: the id of the caller's own identity, which
 *     is its {@code Customer.id} exactly when {@link #isCustomer()} holds — never a
 *     caller-supplied id, and never a username (ADR 002)
 * @param roles    the roles the token was signed with
 */
public record AuthenticatedCaller(long callerId, Set<Role> roles) {

    public AuthenticatedCaller {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * Whether this caller is a Customer, and so whether {@link #callerId()} may be
     * published downstream as a {@code Customer.id}.
     *
     * <p>The single place that answer is given. A role is a capability, not an
     * identity: the domain models one identity (Customer, referenced by
     * identifier only) and three capabilities that can be held separately, so
     * "holds CUSTOMER" is the only sound way to ask whether a caller is one. Every
     * place that would otherwise re-derive it from the role set routes through
     * here instead, so a fourth role cannot quietly widen who gets an
     * {@code X-Customer-Id}.
     */
    public boolean isCustomer() {
        return roles.contains(Role.CUSTOMER);
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

package com.raydans.apigateway.auth;

import java.util.Collection;
import java.util.List;

/**
 * The three roles the platform authorizes against. A role is what a
 * Reservation's Customer, an Event's organizer, and the platform's operator
 * each need to be able to do; everything the gateway permits is one of these.
 *
 * <p>A role is a capability, not an identity. The domain models one kind of
 * identity — the {@code Customer}, referenced by identifier only (CONTEXT.md) —
 * so holding {@link #CUSTOMER} is what makes a caller one, and holding
 * {@link #ORGANIZER} or {@link #ADMIN} does not. Organizer and admin are what
 * people can do here, not things that are separately recorded; a gateway that
 * published an {@code X-Customer-Id} for either would be claiming an identity
 * the domain never gave them.
 *
 * <p>The names are the wire format: they appear verbatim in the token's
 * {@code roles} claim and in the {@code X-User-Roles} header the downstream
 * services trust (ADR 002), so a rename here is a change to that contract and
 * not a refactor internal to this module.
 */
public enum Role {

    /**
     * Is a {@code Customer}, and buys Seats: browses Events, holds Seats, reads
     * their own Reservations, Payments and Notifications. The only role whose
     * holder is given an {@code X-Customer-Id}, because it is the only one whose
     * holder has a Customer id.
     */
    CUSTOMER,

    /**
     * Owns an Event's inventory, so creates Events and changes their Seats.
     */
    ORGANIZER,

    /**
     * Operates the platform: everything an organizer may do, plus the
     * management endpoints the gateway otherwise keeps to itself.
     */
    ADMIN;

    /**
     * The wire form of a set of roles. Sorted so that a claim and the header
     * derived from it are the same answer to "what does this caller hold" rather
     * than two that differ by set iteration order.
     */
    public static List<String> namesOf(Collection<Role> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}

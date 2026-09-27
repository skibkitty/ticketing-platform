package com.raydans.apigateway.auth;

/**
 * The three roles the platform authorizes against. A role is what a
 * Reservation's Customer, an Event's organizer, and the platform's operator
 * each need to be able to do; everything the gateway permits is one of these.
 *
 * <p>The names are the wire format: they appear verbatim in the token's
 * {@code roles} claim and in the {@code X-User-Roles} header the downstream
 * services trust (ADR 002), so a rename here is a change to that contract and
 * not a refactor internal to this module.
 */
public enum Role {

    /**
     * Buys Seats: browses Events, holds Seats, reads their own Reservations,
     * Payments and Notifications.
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
    ADMIN
}

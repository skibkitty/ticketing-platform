package com.raydans.apigateway.auth;

import java.time.Instant;

/**
 * A freshly minted token and the moment it stops being valid.
 *
 * <p>The expiry is returned alongside the token rather than left for the client
 * to decode out of the token's claims: a client that has to parse its own
 * credential to know when to refresh is a client with a second implementation
 * of the claim layout, and the one that will drift.
 *
 * @param token     the signed JWT to send as {@code Authorization: Bearer ...}
 * @param expiresAt when the gateway will stop accepting it
 */
public record IssuedToken(String token, Instant expiresAt) {}

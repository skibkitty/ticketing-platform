package com.raydans.apigateway.auth;

import java.time.Instant;
import java.util.List;

/**
 * What a successful login returns.
 *
 * <p>The roles are echoed so a client can render the right affordances without
 * decoding the token it was just handed. That is a convenience and nothing more:
 * the roles that count are the ones the gateway enforces on every subsequent
 * request, and a client that renders from this copy while sending something else
 * is the client's bug, not a way around the check.
 *
 * @param token     the signed JWT to send as {@code Authorization: Bearer ...}
 * @param roles     the roles this token carries
 * @param expiresAt when the gateway will stop accepting it
 */
public record LoginResponse(String token, List<String> roles, Instant expiresAt) {}

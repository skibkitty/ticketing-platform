package com.raydans.apigateway.auth;

import java.time.Instant;
import java.util.List;

/**
 * What a successful login returns. The roles are echoed so a client can render
 * affordances without decoding the token; the roles that count are the ones the
 * gateway enforces on every later request.
 *
 * @param token     the signed JWT to send as {@code Authorization: Bearer ...}
 * @param roles     the roles this token carries
 * @param expiresAt when the gateway will stop accepting it
 */
public record LoginResponse(String token, List<String> roles, Instant expiresAt) {}

package com.raydans.apigateway.auth;

import java.time.Instant;

/** A freshly minted token and the moment it stops being valid. */
public record IssuedToken(String token, Instant expiresAt) {}

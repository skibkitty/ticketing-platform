package com.raydans.apigateway.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * The credentials posted to {@code /auth/login}.
 *
 * <p>Validated rather than checked by hand so an absent field is a 400 that
 * names the field, and so the "no such user" and "wrong password" paths stay
 * indistinguishable — see {@link UserDirectory}.
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

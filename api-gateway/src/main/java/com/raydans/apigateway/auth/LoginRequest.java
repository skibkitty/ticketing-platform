package com.raydans.apigateway.auth;

import jakarta.validation.constraints.NotBlank;

/** Validated so an absent field is a 400 that names it. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

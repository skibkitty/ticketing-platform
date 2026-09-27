package com.raydans.apigateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing material and lifetime for the tokens this gateway issues.
 *
 * <p>Validated in the canonical constructor rather than trusted, because this
 * is the one piece of configuration whose failure mode is silent and total: a
 * gateway that booted with a missing or too-short secret would issue tokens
 * nothing can verify, or — worse, with a default quietly substituted — hand out
 * tokens signed with a key published in the repository. Refusing to start says
 * which of those happened.
 *
 * @param secret the HMAC-SHA signing key; must be at least 32 bytes, which is
 *     what HS256 requires and therefore the shortest thing that works
 * @param ttl    how long an issued token stays valid
 */
@ConfigurationProperties(prefix = "app.gateway.jwt")
public record JwtProperties(String secret, Duration ttl) {

    /** HS256 requires a key of at least 256 bits; a shorter one is a configuration error. */
    static final int MINIMUM_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "app.gateway.jwt.secret must be set: the gateway signs every token it issues with it");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("app.gateway.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                    + " bytes for HS256; got " + secret.length() + " characters");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("app.gateway.jwt.ttl must be a positive duration; got " + ttl);
        }
    }
}

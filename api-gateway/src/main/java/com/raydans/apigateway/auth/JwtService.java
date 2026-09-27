package com.raydans.apigateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the platform's tokens. The only place a signature is
 * produced or checked, which is what makes "one place to audit and rotate keys"
 * (ADR 002) true rather than aspirational.
 *
 * <p>Symmetric on purpose. The gateway is the only party that needs to verify a
 * token — the services downstream trust a header instead (ADR 002) — so a
 * signing key that only this process can read is a strictly smaller secret to
 * protect than a private key plus a public key distributed to parties that have
 * no use for it.
 *
 * <p>Every failure to accept a token arrives as {@link InvalidTokenException},
 * and none of them carry their reason outward: see that type for why.
 */
@Component
public class JwtService {

    /** The claim the roles travel in, and the key the header is derived from. */
    public static final String ROLES_CLAIM = "roles";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * The clock is a parameter so a test can hold the gateway still in time and
     * watch a token expire, rather than sleeping for an hour to find out.
     */
    JwtService(JwtProperties properties, Clock clock) {
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = properties.ttl();
        this.clock = clock;
    }

    /**
     * Signs a token for an authenticated caller.
     *
     * <p>The roles go in as their names rather than as an enum ordinal, so the
     * claim stays readable in a log and survives someone reordering this enum.
     */
    public IssuedToken issue(String username, Collection<Role> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        String token = Jwts.builder()
                .subject(username)
                .claim(ROLES_CLAIM, roles.stream().map(Enum::name).sorted().toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies a token and reads the caller out of it.
     *
     * @throws InvalidTokenException if the token is not one this gateway issued
     *     and has not expired — malformed, wrong signature, expired, or carrying
     *     a {@code roles} claim that is not a list of role names
     */
    public AuthenticatedCaller parse(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            // JwtException covers a bad signature, a malformed token and an
            // expired one; IllegalArgumentException is what an empty or
            // null-ish token throws before it is ever parsed.
            throw new InvalidTokenException("Token is not valid", ex);
        }
        return new AuthenticatedCaller(claims.getSubject(), rolesFrom(claims));
    }

    /**
     * Reads the roles claim, refusing anything that is not a list of role names.
     *
     * <p>A token this gateway signed always has the claim in the shape it wrote
     * it, so a claim in any other shape means a token from somewhere else that
     * happened to verify. Treating that as "no roles" would be the quiet
     * failure — a caller with an unreadable claim reaching the downstreams with
     * an empty role list and a route that grants on emptiness. Refusing is the
     * only answer that cannot be mistaken for a decision.
     */
    private Set<Role> rolesFrom(Claims claims) {
        Object raw = claims.get(ROLES_CLAIM);
        if (!(raw instanceof List<?> values)) {
            throw new InvalidTokenException("Token carries no readable '" + ROLES_CLAIM + "' claim", null);
        }
        Set<Role> roles = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String name)) {
                throw new InvalidTokenException("Token carries a non-text '" + ROLES_CLAIM + "' claim entry", null);
            }
            try {
                roles.add(Role.valueOf(name));
            } catch (IllegalArgumentException ex) {
                throw new InvalidTokenException("Token names an unknown role", ex);
            }
        }
        return roles;
    }
}

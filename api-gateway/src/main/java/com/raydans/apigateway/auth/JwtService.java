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
 * <p>Symmetric on purpose: the services downstream trust a header instead of a
 * token (ADR 002), so a key only this process can read is a smaller secret than
 * a keypair handed to parties with no use for it.
 *
 * <p>Every refusal arrives as {@link InvalidTokenException}, carrying no reason
 * outward: see that type for why.
 *
 * <p>The subject is the id of the caller's own identity, never a username
 * (ADR 002), which is why {@link #parse} refuses a token whose subject is not
 * one instead of passing the string on for something downstream to interpret.
 * For a caller that is a Customer that id <em>is</em> its {@code Customer.id};
 * for an organizer or an admin it is that caller's own, and is never published
 * as a Customer's (CONTEXT.md).
 */
@Component
public class JwtService {

    /** The claim the roles travel in, and the key the header is derived from. */
    public static final String ROLES_CLAIM = "roles";

    /**
     * What the subject has to be for a token to be usable: a caller's id is a
     * positive integer the identity store gave out, so anything else in the
     * subject means the token is not one this gateway issued — or came from
     * somewhere else entirely.
     */
    private static final String CALLER_ID_EXPLANATION =
            "Token subject is not a caller id";

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
     * Mints a token whose subject is the caller's own id, which is the identity
     * every downstream header is derived from (ADR 002).
     *
     * <p>The id is passed in rather than looked up from a username here: the
     * subject is an assertion about which identity this is, and the only place
     * entitled to make that assertion is whatever authenticated the caller.
     */
    public IssuedToken issue(long callerId, Collection<Role> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        String token = Jwts.builder()
                .subject(Long.toString(callerId))
                // Roles go in as names rather than enum ordinals, so the claim
                // survives a reorder.
                .claim(ROLES_CLAIM, Role.namesOf(roles))
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
     *     and has not expired — malformed, wrong signature, expired, carrying a
     *     {@code roles} claim that is not a list of role names, or carrying a
     *     subject that is not a caller id
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
        return new AuthenticatedCaller(callerIdFrom(claims), rolesFrom(claims));
    }

    /**
     * Reads the subject as the caller's id it is (ADR 002).
     *
     * <p>Refused rather than repaired, and this is the load-bearing refusal of
     * the whole identity decision: a subject that is not a caller's id cannot be
     * turned into one, because every way of turning it into one — reading it as a
     * username, matching it against a table, dropping the characters that are not
     * digits — invents an identity the signature never asserted. A caller is then
     * refused with 401, instead of reaching a service that would book the seats
     * against a guessed id.
     *
     * <p>Note what is <em>not</em> decided here: whether the subject is a
     * Customer's. The roles claim answers that, and a subject is a valid caller id
     * whether or not its holder is a Customer, so an organizer's token carries one
     * too. Refusing it here would make the gateway unable to say who an organizer
     * is.
     */
    private long callerIdFrom(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException(CALLER_ID_EXPLANATION + ": it carries no subject", null);
        }
        long callerId;
        try {
            callerId = Long.parseLong(subject);
        } catch (NumberFormatException ex) {
            throw new InvalidTokenException(CALLER_ID_EXPLANATION, ex);
        }
        if (callerId < 1) {
            throw new InvalidTokenException(CALLER_ID_EXPLANATION, null);
        }
        return callerId;
    }

    /**
     * Reads the roles claim, refusing anything that is not a list of role names.
     *
     * <p>A token this gateway signed always has the claim in the shape it wrote,
     * so any other shape means a token from elsewhere that happened to verify.
     * Reading that as "no roles" would be the quiet failure: an unreadable claim
     * reaching the downstreams as an empty role list, and a route that grants on
     * emptiness.
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

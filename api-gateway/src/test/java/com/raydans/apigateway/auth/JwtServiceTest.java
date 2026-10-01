package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * The token is the platform's only credential, so these pin the properties a
 * later refactor would be most likely to lose: that a token round-trips, that
 * one signed by someone else is refused, that expiry is real rather than
 * decorative, and that the subject is a Customer id or the token is refused
 * (ADR 002).
 */
class JwtServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final Duration TTL = Duration.ofHours(1);

    private final Instant now = Instant.parse("2026-09-27T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final JwtService tokens = new JwtService(new JwtProperties(SECRET, TTL), clock);

    @Test
    void aTokenRoundTripsBackToTheCallerItWasIssuedFor() {
        IssuedToken issued = tokens.issue(42L, Set.of(Role.CUSTOMER));

        AuthenticatedCaller parsed = tokens.parse(issued.token());

        assertThat(parsed.callerId()).isEqualTo(42L);
        assertThat(parsed.roles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void theSubjectIsTheCustomerIdAndNotTheUsername() {
        // The decision ADR 002 records: `sub` is the internal, immutable
        // Customer.id. A username here would leave every downstream header
        // without anything to put in it but a name the caller chose at signup.
        assertThat(payloadOf(tokens.issue(42L, Set.of(Role.CUSTOMER)).token()))
                .contains("\"sub\":\"42\"");
    }

    @Test
    void aSubjectThatIsNotACustomerIdIsRefused() {
        // Refused at verification rather than passed on: every way of reading
        // an id out of a string like this invents an identity the signature
        // never asserted, and ADR 002 forbids all of them.
        assertThatThrownBy(() -> tokens.parse(signedWithSubject("customer")))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.parse(signedWithSubject("42a")))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.parse(signedWithSubject("4 2")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenWithNoSubjectIsRefused() {
        // A token that verifies and says nothing about who it is for is not a
        // token this gateway issued, and guessing an identity for it is the one
        // thing the boundary must not do.
        assertThatThrownBy(() -> tokens.parse(signedWithSubject(null)))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aSubjectThatIsNotAPositiveIdIsRefused() {
        // Customer ids are handed out by a database sequence and start at 1.
        // Zero and below are the shape an unset or overflowed value takes, and
        // booking seats against one is not an error the downstream can detect.
        assertThatThrownBy(() -> tokens.parse(signedWithSubject("0")))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.parse(signedWithSubject("-42")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void everyRoleSurvivesTheRoundTrip() {
        IssuedToken issued = tokens.issue(1L, Set.of(Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN));

        assertThat(tokens.parse(issued.token()).roles())
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN);
    }

    @Test
    void theTokenSaysWhenItStopsWorking() {
        IssuedToken issued = tokens.issue(42L, Set.of(Role.CUSTOMER));

        // Returned to the client rather than left inside the token, so a client
        // never has to parse its own credential to know when to refresh.
        assertThat(issued.expiresAt()).isEqualTo(now.plus(TTL));
    }

    @Test
    void aTokenSignedWithADifferentSecretIsRefused() {
        JwtService someoneElse = new JwtService(new JwtProperties("a-completely-different-secret-value", TTL), clock);
        String foreignToken = someoneElse.issue(44L, Set.of(Role.ADMIN)).token();

        // The claim on this token says ADMIN. If the signature were not the thing
        // being checked, this would authenticate a platform operator.
        assertThatThrownBy(() -> tokens.parse(foreignToken)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenSignedWithTheRightSecretButTamperedWithIsRefused() {
        String token = tokens.issue(42L, Set.of(Role.CUSTOMER)).token();
        // Swap the payload's roles claim for ADMIN's, leaving the signature as-is.
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"sub\":\"42\",\"roles\":[\"ADMIN\"],\"exp\":"
                                + now.plus(TTL).getEpochSecond() + "}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokens.parse(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        JwtService earlier = new JwtService(new JwtProperties(SECRET, TTL), Clock.fixed(now, ZoneOffset.UTC));
        String token = earlier.issue(42L, Set.of(Role.CUSTOMER)).token();

        // Same key, one second past the token's expiry: the only thing that
        // changed is the time, which is what makes this a test of expiry rather
        // than of the signature.
        JwtService later = new JwtService(new JwtProperties(SECRET, TTL), Clock.fixed(now.plus(TTL).plusSeconds(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenIsStillAcceptedOneSecondBeforeItExpires() {
        String token = tokens.issue(42L, Set.of(Role.CUSTOMER)).token();
        JwtService justBefore = new JwtService(
                new JwtProperties(SECRET, TTL), Clock.fixed(now.plus(TTL).minusSeconds(1), ZoneOffset.UTC));

        assertThat(justBefore.parse(token).callerId()).isEqualTo(42L);
    }

    @Test
    void rubbishIsRefusedRatherThanCrashing() {
        assertThatThrownBy(() -> tokens.parse("not-a-token"))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.parse("")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aSecretTooShortForTheAlgorithmIsRefusedAtStartup() {
        // Better a gateway that will not start than one signing with a key the
        // algorithm is entitled to reject, or quietly substituting a default.
        // The minimum length is enforced even though the key is now supplied by
        // the environment, because a short one is a signing key anyone could
        // guess.
        assertThatThrownBy(() -> new JwtProperties("too-short", TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aMissingSecretIsRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(null, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void aNonPositiveLifetimeIsRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A correctly signed token carrying whatever subject this test wants, which
     * {@link #issue} cannot produce because it only ever speaks in Customer ids.
     * Without this seam the only way to test the subject check would be to
     * change the production code to allow the shape under test.
     */
    private String signedWithSubject(String subject) {
        JwtBuilder builder = Jwts.builder()
                .claim(JwtService.ROLES_CLAIM, Role.namesOf(Set.of(Role.CUSTOMER)))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(signingKey()).compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a token's payload back without verifying it, for asserting what was written. */
    private static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}

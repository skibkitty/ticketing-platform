package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The token is the platform's only credential, so these pin the properties a
 * later refactor would be most likely to lose: that a token round-trips, that
 * one signed by someone else is refused, and that expiry is real rather than
 * decorative.
 */
class JwtServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final Duration TTL = Duration.ofHours(1);

    private final Instant now = Instant.parse("2026-09-27T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final JwtService tokens = new JwtService(new JwtProperties(SECRET, TTL), clock);

    @Test
    void aTokenRoundTripsBackToTheCallerItWasIssuedFor() {
        IssuedToken issued = tokens.issue("customer", Set.of(Role.CUSTOMER));

        AuthenticatedCaller parsed = tokens.parse(issued.token());

        assertThat(parsed.username()).isEqualTo("customer");
        assertThat(parsed.roles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void everyRoleSurvivesTheRoundTrip() {
        IssuedToken issued = tokens.issue("admin", Set.of(Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN));

        assertThat(tokens.parse(issued.token()).roles())
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN);
    }

    @Test
    void theTokenSaysWhenItStopsWorking() {
        IssuedToken issued = tokens.issue("customer", Set.of(Role.CUSTOMER));

        // Returned to the client rather than left inside the token, so a client
        // never has to parse its own credential to know when to refresh.
        assertThat(issued.expiresAt()).isEqualTo(now.plus(TTL));
    }

    @Test
    void aTokenSignedWithADifferentSecretIsRefused() {
        JwtService someoneElse = new JwtService(new JwtProperties("a-completely-different-secret-value", TTL), clock);
        String foreignToken = someoneElse.issue("admin", Set.of(Role.ADMIN)).token();

        // The claim on this token says ADMIN. If the signature were not the thing
        // being checked, this would authenticate a platform operator.
        assertThatThrownBy(() -> tokens.parse(foreignToken)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenSignedWithTheRightSecretButTamperedWithIsRefused() {
        String token = tokens.issue("customer", Set.of(Role.CUSTOMER)).token();
        // Swap the payload's roles claim for ADMIN's, leaving the signature as-is.
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"sub\":\"customer\",\"roles\":[\"ADMIN\"],\"exp\":"
                                + now.plus(TTL).getEpochSecond() + "}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokens.parse(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        JwtService earlier = new JwtService(new JwtProperties(SECRET, TTL), Clock.fixed(now, ZoneOffset.UTC));
        String token = earlier.issue("customer", Set.of(Role.CUSTOMER)).token();

        // Same key, one second past the token's expiry: the only thing that
        // changed is the time, which is what makes this a test of expiry rather
        // than of the signature.
        JwtService later = new JwtService(new JwtProperties(SECRET, TTL), Clock.fixed(now.plus(TTL).plusSeconds(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenIsStillAcceptedOneSecondBeforeItExpires() {
        String token = tokens.issue("customer", Set.of(Role.CUSTOMER)).token();
        JwtService justBefore = new JwtService(
                new JwtProperties(SECRET, TTL), Clock.fixed(now.plus(TTL).minusSeconds(1), ZoneOffset.UTC));

        assertThat(justBefore.parse(token).username()).isEqualTo("customer");
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
}

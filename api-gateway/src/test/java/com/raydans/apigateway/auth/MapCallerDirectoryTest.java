package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The caller ids the directory is configured with, which have to be one caller
 * each.
 *
 * <p>A caller's id is the token's subject, and the subject is the identity every
 * downstream header is derived from (ADR 002). So {@code username → caller-id}
 * is the platform's identity store, and a duplicate entry in it does not make
 * one of the two callers unusable — it makes them <em>interchangeable</em>.
 * Both logins succeed, both are issued a token with the same {@code sub}, and
 * every service downstream acts on the number, so one Customer's reservations
 * and notifications would turn up under another. Nothing crashes; the damage is
 * only visible as data belonging to the wrong person, which is the hardest kind
 * of bug to trace back to the configuration line that caused it.
 *
 * <p>The tests are here rather than in {@link LoginControllerTest} because the
 * rule is a property of the directory's configuration, not of the login
 * surface: the login surface is what the consequences show up in, and it is
 * covered there for the ids that are distinct.
 */
class MapCallerDirectoryTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final JwtService tokens =
            new JwtService(new JwtProperties(SECRET, TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void twoCallersConfiguredWithTheSameCallerIdRefuseToStart() {
        // alice and bob are two accounts that the operator created and the
        // gateway cannot tell apart. Choosing either one of them as "the"
        // owner of 42 would be inventing an identity the operator never asked
        // for, so the gateway refuses the configuration and says which number
        // and which logins are involved.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "alice", new CallerDirectoryProperties.Credentials(42L, "alice", List.of("CUSTOMER")),
                "bob", new CallerDirectoryProperties.Credentials(42L, "bob", List.of("CUSTOMER")))));

        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("alice")
                .hasMessageContaining("bob");
    }

    @Test
    void aCallerIdSharedByAThirdCallerNamesAllOfThem() {
        // Three logins on one id is the same defect with one more victim, and a
        // message naming only two of them would send the operator looking for
        // the third in the wrong place.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "alice", new CallerDirectoryProperties.Credentials(7L, "alice", List.of("CUSTOMER")),
                "bob", new CallerDirectoryProperties.Credentials(7L, "bob", List.of("ORGANIZER")),
                "carol", new CallerDirectoryProperties.Credentials(7L, "carol", List.of("ADMIN")))));

        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7")
                .hasMessageContaining("alice")
                .hasMessageContaining("bob")
                .hasMessageContaining("carol");
    }

    @Test
    void aDuplicateIdIsRefusedEvenWhenOnlyOneOfTheCallersHoldsTheCustomerRole() {
        // A caller id is not a Customer id — only a caller holding CUSTOMER has
        // one (CONTEXT.md) — so it is tempting to treat a collision between an
        // organizer and a customer as harmless. It is the opposite: the
        // organizer's id is published nowhere, so nothing downstream would ever
        // notice the overlap, and the moment that organizer is also given
        // CUSTOMER the two are the same identity with a customer-facing
        // consequence.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "customer", new CallerDirectoryProperties.Credentials(42L, "customer", List.of("CUSTOMER")),
                "organizer", new CallerDirectoryProperties.Credentials(42L, "organizer", List.of("ORGANIZER")))));

        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42");
    }

    @Test
    void theSameConfigurationAlwaysReportsTheSameCallers() {
        // The credentials map has no order of its own, so an unsorted message
        // would name the same two callers in a different order from one start to
        // the next. Flaky error messages are how a startup check stops being
        // read.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "zoe", new CallerDirectoryProperties.Credentials(9L, "zoe", List.of("CUSTOMER")),
                "adam", new CallerDirectoryProperties.Credentials(9L, "adam", List.of("CUSTOMER")))));

        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .hasMessageContaining("'adam', 'zoe'");
    }

    @Test
    void theDuplicateIsReportedAfterTheCallerThatHasNoIdAtAll() {
        // Both are configuration faults and only one can be reported, so the
        // order matters: a caller whose id never bound is a more specific
        // mistake than a collision on the placeholder, and the operator fixing
        // that first may be looking at a configuration that is now valid.
        Map<String, CallerDirectoryProperties.Credentials> callers = new LinkedHashMap<>();
        callers.put("alice", new CallerDirectoryProperties.Credentials(42L, "alice", List.of("CUSTOMER")));
        callers.put("bob", new CallerDirectoryProperties.Credentials(null, "bob", List.of("CUSTOMER")));
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(callers));

        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bob")
                .hasMessageContaining("caller-id")
                .hasMessageNotContaining("alice");
    }

    @Test
    void distinctCallerIdsAreAcceptedAndEachLoginResolvesToItsOwn() {
        // The other half: the check has to be quiet about a directory that is
        // right, or it is a rule that gets switched off.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "customer", new CallerDirectoryProperties.Credentials(42L, "customer", List.of("CUSTOMER")),
                "organizer", new CallerDirectoryProperties.Credentials(43L, "organizer", List.of("ORGANIZER")),
                "admin", new CallerDirectoryProperties.Credentials(44L, "admin", List.of("ADMIN")))));

        assertThatCode(directory::refuseToStartWithUnusableCallers).doesNotThrowAnyException();
        assertThat(directory.findByUsername("customer").orElseThrow().callerId()).isEqualTo(42L);
        assertThat(directory.findByUsername("organizer").orElseThrow().callerId()).isEqualTo(43L);
        assertThat(directory.findByUsername("admin").orElseThrow().callerId()).isEqualTo(44L);
    }

    @Test
    void theTokensBothCallersWouldHaveBeenGivenAreIndistinguishable() {
        // What the duplicate would have cost, shown by minting the two tokens a
        // directory like that hands out. Without a startup check this is not a
        // contradiction anyone notices: both tokens verify, both are accepted,
        // and both say the same thing about who is asking.
        MapCallerDirectory directory = new MapCallerDirectory(new CallerDirectoryProperties(Map.of(
                "alice", new CallerDirectoryProperties.Credentials(42L, "alice", List.of("CUSTOMER")),
                "bob", new CallerDirectoryProperties.Credentials(42L, "bob", List.of("CUSTOMER")))));

        CallerCredentials alice = directory.findByUsername("alice").orElseThrow();
        CallerCredentials bob = directory.findByUsername("bob").orElseThrow();
        String aliceToken = tokens.issue(alice.callerId(), alice.roles()).token();
        String bobToken = tokens.issue(bob.callerId(), bob.roles()).token();

        assertThat(tokens.parse(aliceToken).callerId()).isEqualTo(tokens.parse(bobToken).callerId());
        assertThatThrownBy(directory::refuseToStartWithUnusableCallers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sub=42");
    }

    @Test
    void aContextStartedWithTwoCallersOnOneIdFailsToStart() {
        // The requirement is that the gateway does not come up at all, so the
        // check is made through the container that actually runs it rather than
        // by calling the method a test happens to know the name of. A
        // configuration that fails to be noticed is a configuration that ships.
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(
                CallerDirectoryProperties.class,
                () -> new CallerDirectoryProperties(Map.of(
                        "alice", new CallerDirectoryProperties.Credentials(42L, "alice", List.of("CUSTOMER")),
                        "bob", new CallerDirectoryProperties.Credentials(42L, "bob", List.of("CUSTOMER")))));
        context.register(MapCallerDirectory.class);
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("42")
                    .hasMessageContaining("alice")
                    .hasMessageContaining("bob");
        } finally {
            context.close();
        }
    }
}

package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The login limiter on its own, with a clock it can move.
 *
 * <p>The behaviour that matters is not "there is a limit" but which attempts the
 * limit stops and which it must not: it has to count an unknown login name
 * exactly as it counts a real one (or it becomes an oracle for which usernames
 * exist, #43), it has to refuse a caller over the limit even when the password is
 * right, and it must not let a refused caller keep its own lockout alive by
 * continuing to try. All three are statements about the passage of time, which is
 * why the clock is a parameter — waiting five real minutes to check them would be
 * a test nobody runs.
 *
 * <p>The limits are the ones {@code application.yml} ships. Restating them is the
 * only way this class can be a unit test, and the risk of the two copies drifting
 * is answered in the other direction: the HTTP-level behaviour is asserted in
 * {@code GatewayProxyBootTests} against whatever the deployment actually binds.
 */
class LoginThrottleTest {

    private static final int MAX_PER_ACCOUNT = 5;
    private static final int MAX_PER_ADDRESS = 20;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static final String ADDRESS = "203.0.113.7";

    private MovableClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-01T12:00:00Z"));
        throttle = new LoginThrottle(
                new LoginThrottleProperties(MAX_PER_ACCOUNT, MAX_PER_ADDRESS, WINDOW), clock);
    }

    @Test
    void theConfiguredNumberOfGuessesIsAnsweredAndTheNextOneIsRefused() {
        assertThat(attemptAtWhichRefusalsStart(ADDRESS, "customer"))
                .isEqualTo(MAX_PER_ACCOUNT + 1);
    }

    @Test
    void anAttemptOverTheAccountBudgetIsRefusedBeforeAnythingIsCompared() {
        lockOutAccount(ADDRESS, "customer");

        // Not counted at all: the point of asking before the password is read is
        // that a refused caller gets the same answer whatever it was going to say.
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "customer"))
                .isInstanceOf(ThrottledLoginException.class)
                .extracting(ex -> ((ThrottledLoginException) ex).retryAfterSeconds())
                .isEqualTo(WINDOW.toSeconds());
    }

    @Test
    void theAllowanceComesBackWhenTheWindowRunsOut() {
        lockOutAccount(ADDRESS, "customer");

        clock.advance(WINDOW);

        assertThatCode(() -> throttle.refuseWhenThrottled(ADDRESS, "customer")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.recordFailure(ADDRESS, "customer")).doesNotThrowAnyException();
    }

    @Test
    void aRefusedAttemptDoesNotPushTheWindowAlong() {
        // The lockout has to expire on its own. If a refused attempt counted, an
        // attacker who keeps trying would hold a legitimate caller locked out for
        // as long as it cared to, which turns the limiter into a way to deny
        // service to one named account for free.
        Instant firstGuessAt = clock.instant();
        lockOutAccount(ADDRESS, "customer");

        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "customer"))
                    .isInstanceOf(ThrottledLoginException.class);
        }

        // Still the window the first guess opened, ending five minutes after it —
        // not five minutes after the fiftieth attempt, which would be a quarter
        // of an hour of lockout bought by continuing to hammer.
        assertThat(whenThisStopsBeingRefused(ADDRESS, "customer")).isEqualTo(firstGuessAt.plus(WINDOW));
    }

    @Test
    void anAccountBudgetIsSeparatePerAddress() {
        // Two hosts guessing one account: each has its own counter, so neither
        // trips the other's and the limit is on the account rather than on the
        // planet.
        for (int attempt = 1; attempt <= MAX_PER_ACCOUNT; attempt++) {
            throttle.recordFailure("198.51.100.1", "customer");
        }

        assertThatCode(() -> throttle.refuseWhenThrottled("198.51.100.2", "customer")).doesNotThrowAnyException();
    }

    @Test
    void oneHostGuessingManyAccountsStaysBoundedByItsAddressBudget() {
        // The other direction, and the reason there are two budgets at all.
        // Per-account limiting alone would answer all twenty of these: each is the
        // first guess against a different name, so no account counter moves past
        // its own limit. Varying the username must not be a way out — this is what
        // stops one host walking the whole caller set.
        for (int attempt = 1; attempt <= MAX_PER_ADDRESS; attempt++) {
            throttle.recordFailure(ADDRESS, "account-" + attempt);
        }

        assertThatThrownBy(() -> throttle.recordFailure(ADDRESS, "account-1"))
                .as("no account counter moved past its limit, so the address budget is what stops this")
                .isInstanceOf(ThrottledLoginException.class);
    }

    @Test
    void anAccountBeingGuessedDoesNotLockOutOtherAccounts() {
        // The cost of a per-account limit being per-account: someone hammering one
        // login name does not freeze sign-in for everybody else, which is what a
        // global "too many logins" counter would do. Deliberate — the address
        // budget is what bounds an attacker who does not care which name they
        // guess, and it is still spent by every one of these attempts.
        lockOutAccount(ADDRESS, "customer");

        assertThatCode(() -> throttle.refuseWhenThrottled(ADDRESS, "organizer")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.recordFailure(ADDRESS, "organizer")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulLoginHandsBackTheAccountsBudgetAndNotTheHosts() {
        lockOutAccount(ADDRESS, "customer");
        clock.advance(WINDOW);
        for (int attempt = 1; attempt <= MAX_PER_ADDRESS; attempt++) {
            throttle.recordFailure(ADDRESS, "account-" + attempt);
        }
        // Over this host's budget, which is the state the assertions below are
        // read against.
        assertThatThrownBy(() -> throttle.recordFailure(ADDRESS, "account-1"))
                .isInstanceOf(ThrottledLoginException.class);

        throttle.recordSuccess("customer");

        // The account's budget is back: from a host that has spent nothing, this
        // name is answerable again.
        assertThatCode(() -> throttle.refuseWhenThrottled("198.51.100.50", "customer")).doesNotThrowAnyException();
        // The host that spent its budget has not had it refunded. Clearing that
        // too would hand anyone holding one valid credential a way out: alternate
        // a correct login with a wrong guess and neither limit is ever reached.
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "customer"))
                .isInstanceOf(ThrottledLoginException.class);
    }

    @Test
    void anUnknownLoginNameIsCountedExactlyAsAKnownOneIs() {
        // #43's acceptance, on the limiter's own terms. If a name that exists
        // tripped sooner than one that does not, the limit itself would answer
        // "which usernames are real" — the oracle the identical error body and
        // the constant-time compare exist to prevent. Nothing here asks whether an
        // account exists, so the two refuse on the same attempt.
        assertThat(attemptAtWhichRefusalsStart("198.51.100.1", "nobody")).isEqualTo(MAX_PER_ACCOUNT + 1);
        assertThat(attemptAtWhichRefusalsStart("198.51.100.2", "customer")).isEqualTo(MAX_PER_ACCOUNT + 1);
    }

    @Test
    void whitespaceInALoginNameDoesNotBuyAFreshBudget() {
        lockOutAccount(ADDRESS, "customer");

        // The key is stripped, so padding the name is not a way to start again.
        // Case is deliberately not folded — see LoginThrottle#accountKey — so
        // guessing Customer and customer separately is left to the address budget.
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "customer "))
                .isInstanceOf(ThrottledLoginException.class);
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "  customer"))
                .isInstanceOf(ThrottledLoginException.class);
    }

    @Test
    void windowsThatHaveRunOutAreForgotten() {
        // Otherwise the maps grow with every name and address anyone has ever
        // failed from, and hold them for the life of the process.
        throttle.recordFailure(ADDRESS, "customer");
        throttle.recordFailure("198.51.100.9", "other");

        assertThat(throttle.trackedAccountWindows()).isEqualTo(2);
        assertThat(throttle.trackedAddressWindows()).isEqualTo(2);

        clock.advance(WINDOW);

        assertThatCode(() -> throttle.refuseWhenThrottled(ADDRESS, "customer")).doesNotThrowAnyException();
        assertThat(throttle.trackedAccountWindows()).isZero();
        assertThat(throttle.trackedAddressWindows()).isZero();
    }

    @Test
    void aCallerWhoseAddressTheContainerCouldNotSayIsStillLimited() {
        // getRemoteAddr() can be null for a client that has gone away mid-request.
        // An unhandled failure there would be a 500 and a prompt to try again;
        // a bucket of its own would be a free budget.
        for (int attempt = 1; attempt <= MAX_PER_ACCOUNT; attempt++) {
            throttle.recordFailure(null, "customer");
        }
        assertThatThrownBy(() -> throttle.recordFailure(null, "customer"))
                .isInstanceOf(ThrottledLoginException.class);
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(null, "customer"))
                .isInstanceOf(ThrottledLoginException.class);
        // One window, not one per attempt: they are all the same caller as far as
        // anything here can tell.
        assertThat(throttle.trackedAddressWindows()).isEqualTo(1);
    }

    @Test
    void retryAfterIsNeverZeroSeconds() {
        lockOutAccount(ADDRESS, "customer");
        clock.advance(WINDOW.minusSeconds(1));

        // A second before the window ends. Retry-After: 0 would tell the client to
        // come straight back and be refused again.
        assertThatThrownBy(() -> throttle.refuseWhenThrottled(ADDRESS, "customer"))
                .isInstanceOf(ThrottledLoginException.class)
                .extracting(ex -> ((ThrottledLoginException) ex).retryAfterSeconds())
                .isEqualTo(1L);
    }

    // --- the configuration, which has to fail loudly rather than be absent ----

    @Test
    void aLimitOfZeroIsRefusedRatherThanMeaningNoAttempts() {
        // Zero would answer nobody's login at all, which is a decision about
        // availability and not something a missing or misspelt key should be able
        // to make. A gateway that refuses to start says so.
        assertThatThrownBy(() -> new LoginThrottleProperties(0, MAX_PER_ADDRESS, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-failures-per-account");
        assertThatThrownBy(() -> new LoginThrottleProperties(MAX_PER_ACCOUNT, -1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-failures-per-address");
    }

    @Test
    void aWindowOfZeroIsRefusedRatherThanCountingNothing() {
        assertThatThrownBy(() -> new LoginThrottleProperties(MAX_PER_ACCOUNT, MAX_PER_ADDRESS, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new LoginThrottleProperties(MAX_PER_ACCOUNT, MAX_PER_ADDRESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Drives one account to a refused state: the configured number of guesses is
     * answered and the attempt after it is refused. So a lockout takes one more
     * failure than the limit allows, which is what makes "five failures" mean
     * "the sixth attempt is refused" rather than "the fifth is".
     */
    private void lockOutAccount(String address, String username) {
        for (int attempt = 1; attempt <= MAX_PER_ACCOUNT; attempt++) {
            throttle.recordFailure(address, username);
        }
        assertThatThrownBy(() -> throttle.recordFailure(address, username))
                .isInstanceOf(ThrottledLoginException.class);
    }

    /**
     * Plays a caller logging in against one name from one host, in the order
     * {@link LoginController} does it — asked first, counted only if answered —
     * and returns the attempt that was refused.
     *
     * <p>Written as whole attempts rather than as calls to the two halves because
     * the number that matters is a property of the sequence: "how many guesses
     * does this caller get" is not answerable by asking one method or the other.
     */
    private int attemptAtWhichRefusalsStart(String address, String username) {
        for (int attempt = 1; attempt <= MAX_PER_ADDRESS + 2; attempt++) {
            try {
                throttle.refuseWhenThrottled(address, username);
                throttle.recordFailure(address, username);
            } catch (ThrottledLoginException refused) {
                return attempt;
            }
        }
        throw new AssertionError("no attempt was ever refused for '" + username + "'");
    }

    /**
     * When this pair stops being refused, found by advancing the clock the limiter
     * actually reads rather than by reading a counter out of it — so the answer is
     * a question about the limiter's behaviour and not a restatement of its state.
     */
    private Instant whenThisStopsBeingRefused(String address, String username) {
        for (int second = 0; second <= WINDOW.toSeconds(); second++) {
            try {
                throttle.refuseWhenThrottled(address, username);
                return clock.instant();
            } catch (ThrottledLoginException stillRefused) {
                clock.advance(Duration.ofSeconds(1));
            }
        }
        throw new AssertionError("the lockout on '" + username + "' never ended");
    }

    /**
     * A clock the test moves by hand. The limiter's whole behaviour is a function
     * of when a window ends, and this is the only way to ask a question about a
     * five-minute window without spending five minutes.
     */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            this.now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
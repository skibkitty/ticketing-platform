package com.raydans.apigateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How many credential guesses this gateway will answer, counted on two
 * independent axes (ADR 015).
 *
 * <p>The per-account axis is the obvious one and is not sufficient: it stops an
 * attacker working on one login name and does nothing about one attacker working
 * on a thousand names. The per-address axis is the one neither dimension can
 * escape, because changing the username only starts a counter for a
 * <em>different</em> account while the address counter keeps counting the same
 * attempts. Neither alone covers both attacks; both are cheap.
 *
 * <p><strong>Failures are counted for every attempt, whether or not the login
 * name exists.</strong> That is what keeps the limiter from becoming the oracle
 * the login surface is built to avoid (#43): an account counter that only moved
 * for real accounts would answer "this name exists" by refusing sooner than it
 * refuses a name nobody has, and reading which usernames exist is exactly what
 * the constant-time comparison and the identical error body exist to prevent. So
 * nothing here asks whether an account exists, and a caller cannot tell from the
 * timing of the limit whether their guess was close.
 *
 * <p>Which also fixes the shape of a refusal: a caller over the limit is refused
 * before any credential is compared, so a correct password is refused exactly
 * like a wrong one. A limiter that spared the caller holding the right password
 * would be a password oracle with a rate limit on it.
 *
 * <p>Counters live in this process, which is the honest limit of the design: a
 * deployment running N gateways behind a load balancer gets the configured
 * budget per replica, so an attacker can spend N times it, and nothing is shared
 * between replicas or across a restart. A deployment that needs a limit to mean
 * one number needs a shared store, which is a different decision and not one
 * this class pretends to have made.
 */
@Component
public class LoginThrottle {

    /**
     * Where a request came from when the container could not say. Shared on
     * purpose: a caller whose address is unrecordable gets the conservative
     * answer — the same budget as every other such caller — rather than a budget
     * of its own, or a failure that answers 500 and tells it to try again.
     */
    private static final String UNRECORDED_ADDRESS = "unrecorded";

    private final LoginThrottleProperties limits;
    private final Clock clock;

    private final ConcurrentMap<String, Window> failuresByAccount = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Window> failuresByAddress = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottle(LoginThrottleProperties limits) {
        this(limits, Clock.systemUTC());
    }

    /**
     * The clock is a parameter so a test can watch a window expire rather than
     * sleeping through it, the same seam {@link JwtService} has for token expiry.
     */
    LoginThrottle(LoginThrottleProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Whether this attempt may proceed — asked before the password is read, so a
     * refused caller learns nothing about its own credentials.
     *
     * @throws ThrottledLoginException if either axis is spent, carrying when the
     *     limiter will answer again
     */
    public void refuseWhenThrottled(String remoteAddress, String username) {
        Instant now = clock.instant();
        sweep(failuresByAccount, now);
        sweep(failuresByAddress, now);

        Instant blocked = latestOf(
                spentUntil(
                        failuresByAccount.get(accountKey(username)), now, limits.maxFailuresPerAccount()),
                spentUntil(
                        failuresByAddress.get(addressKey(remoteAddress)), now, limits.maxFailuresPerAddress()));

        if (blocked != null) {
            throw new ThrottledLoginException(secondsUntil(blocked, now));
        }
    }

    /**
     * Counts one failed guess on both axes, and refuses the attempt that takes
     * either over its budget.
     *
     * <p>Counted before the refusal rather than after, so the limit is "this many
     * guesses are answered and the next one is not" rather than one attempt more
     * than it looks. Attempt refused <em>before</em> this — because it was
     * already over — never reaches here, which is what keeps a caller hammering a
     * spent window from pushing its expiry along: it would otherwise be able to
     * hold a legitimate caller locked out for as long as it kept trying.
     *
     * @throws ThrottledLoginException if this failure took either axis over
     */
    public void recordFailure(String remoteAddress, String username) {
        Instant now = clock.instant();

        Window account = countOne(failuresByAccount, accountKey(username), now);
        Window address = countOne(failuresByAddress, addressKey(remoteAddress), now);

        Instant blocked = latestOf(
                spentUntil(account, now, limits.maxFailuresPerAccount()),
                spentUntil(address, now, limits.maxFailuresPerAddress()));

        if (blocked != null) {
            throw new ThrottledLoginException(secondsUntil(blocked, now));
        }
    }

    /**
     * Clears the account's window after a successful login.
     *
     * <p>The account's and not the address's, and the difference is the whole
     * reason this method is not a blanket reset. Clearing the address window too
     * would hand an attacker holding one valid credential a way out: alternate
     * one correct login with one wrong guess and the host's budget never reaches
     * its limit. The address budget is spent by failing attempts and only ever
     * expires, so it costs a legitimate caller behind one NAT address a little
     * patience and costs an attacker the budget they were counting on.
     */
    public void recordSuccess(String username) {
        failuresByAccount.remove(accountKey(username));
    }

    /** @return the window for this key, started if there is none or it has run out */
    private Window countOne(ConcurrentMap<String, Window> windows, String key, Instant now) {
        return windows.compute(key, (ignored, existing) -> existing == null || existing.isOver(now)
                ? new Window(1, now.plus(limits.window()))
                : new Window(existing.failures() + 1, existing.endsAt()));
    }

    /**
     * @return when the window runs out if it is spent, or null if it is not — so
     * that an attempt is refused when either axis says so and the two answers
     * cannot be told apart by the caller
     */
    private static Instant spentUntil(Window window, Instant now, int max) {
        if (window == null || window.isOver(now) || window.failures() <= max) {
            return null;
        }
        return window.endsAt();
    }

    /**
     * Drops windows that have run out, on every call.
     *
     * <p>Without this the maps are keyed by whatever a caller sends, so they grow
     * with the number of distinct names and addresses anyone has ever failed from
     * and never shrink. A window outlives its usefulness by nothing: once it has
     * ended it is indistinguishable from one that never existed.
     */
    private static void sweep(ConcurrentMap<String, Window> windows, Instant now) {
        windows.entrySet().removeIf(entry -> entry.getValue().isOver(now));
    }

    /** @return the later of two nullable instants, i.e. when both limits have cleared */
    private static Instant latestOf(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static long secondsUntil(Instant endsAt, Instant now) {
        // At least one second. A Retry-After of 0 tells the client to come
        // straight back and be refused again, which is the opposite of what the
        // header is for, and it is what truncation to whole seconds produces as
        // the window is about to end.
        return Math.max(1L, Duration.between(now, endsAt).toSeconds());
    }

    /**
     * The remote address, and nothing else.
     *
     * <p>{@code X-Forwarded-For} is never read, and that is the whole reason the
     * per-address axis is worth having. This platform has exactly one hop and
     * trusts nothing, so every forwarded header on the request was chosen by
     * whoever sent it: keying on one would make the address budget a number the
     * attacker sets, and the honest-looking implementation would be the bypass.
     */
    private static String addressKey(String remoteAddress) {
        return remoteAddress == null ? UNRECORDED_ADDRESS : remoteAddress;
    }

    /**
     * A digest of the login name rather than the name.
     *
     * <p>For its length, which is the caller's to choose and which nothing above
     * this caps, so the plain string would let a caller decide how much the
     * gateway holds on to; and because a heap dump of the limiter should not be a
     * list of this platform's login names.
     *
     * <p>Surrounding whitespace is stripped, so it is not a way to give one
     * account a fresh budget per attempt. Case is deliberately <em>not</em>
     * folded: the key is then exactly what {@link MapCallerDirectory} looks up, so
     * the limiter and the credential check agree about what a login name is, and
     * guessing {@code Customer} and {@code customer} separately is caught by the
     * address budget rather than by a rule about spelling that would only hold
     * while nobody typed the other one.
     */
    private static String accountKey(String username) {
        return sha256Hex(username.strip());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform is required to provide SHA-256, so this is a
            // broken runtime rather than a condition to handle.
            throw new IllegalStateException("SHA-256 is required and this runtime does not provide it", ex);
        }
    }

    /** @return how many account windows are being tracked, for {@code LoginThrottleTest} */
    int trackedAccountWindows() {
        return failuresByAccount.size();
    }

    /** @return how many address windows are being tracked, for {@code LoginThrottleTest} */
    int trackedAddressWindows() {
        return failuresByAddress.size();
    }

    /**
     * Failures counted in one fixed window. A window rather than a decaying
     * average because the property wanted is "not more than N guesses in any
     * five minutes", and an average would let an attacker spend its whole budget
     * in a burst and then pass the next one.
     */
    private record Window(int failures, Instant endsAt) {

        boolean isOver(Instant now) {
            return !now.isBefore(endsAt);
        }
    }
}
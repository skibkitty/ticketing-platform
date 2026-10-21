package com.raydans.apigateway.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many login guesses this gateway will answer before it stops, bound from
 * {@code app.gateway.login-throttle.*}.
 *
 * <p>Two budgets rather than one, because the two attacks are different and one
 * number cannot cover both (ADR 015). {@code max-failures-per-address} stops a
 * single host guessing at everything; {@code max-failures-per-account} stops one
 * account being guessed from a botnet, which per-address limiting cannot see at
 * all.
 *
 * <p>Validated rather than defaulted in code because the failure being prevented
 * is the one a missing value would cause. A limiter whose limits were optional
 * would be a limiter a deployment can switch off by leaving a key out of its
 * configuration — a block that fails to bind, a typo in a name, a partial
 * override all produce zero, and zero here reads as "refuse every login" until
 * somebody notices the gateway is unusable and lowers it. Refusing to start says
 * which of those happened. The values in {@code application.yml} are the
 * defaults; a deployment states its own.
 *
 * @param maxFailuresPerAccount failures against one login name in one window
 *     before further attempts for that name are refused rather than answered
 * @param maxFailuresPerAddress failures from one remote address in one window,
 *     which is what bounds an attacker who changes the name on every attempt
 * @param window               how long a failure is remembered for
 */
@ConfigurationProperties(prefix = "app.gateway.login-throttle")
public record LoginThrottleProperties(int maxFailuresPerAccount, int maxFailuresPerAddress, Duration window) {

    public LoginThrottleProperties {
        requirePositive(maxFailuresPerAccount, "max-failures-per-account");
        requirePositive(maxFailuresPerAddress, "max-failures-per-address");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "app.gateway.login-throttle.window must be a positive duration; got " + window
                            + ". A window of zero would count nothing and leave the login surface unlimited.");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("app.gateway.login-throttle." + name + " must be at least 1; got "
                    + value + ". Zero would mean answering no login attempt at all, which is a decision about"
                    + " availability rather than one a missing configuration value should be able to make.");
        }
    }
}
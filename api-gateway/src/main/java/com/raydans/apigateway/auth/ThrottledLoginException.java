package com.raydans.apigateway.auth;

/**
 * The login surface has stopped answering guesses for now.
 *
 * <p>Thrown for every caller the throttle refuses, whether the attempt was going
 * to succeed or not, and said in the same words either way. Both halves are
 * load-bearing: a limiter that spared a caller holding the right password would
 * turn the limit into an oracle — "throttled unless my password is correct" is a
 * password oracle with a rate limit on it, and it is the one thing this class
 * must not become.
 *
 * <p>Carries the {@code Retry-After} rather than letting a handler guess it,
 * because the window that tripped is the only thing that knows when it ends, and
 * a handler that computed its own would drift from the counter it is answering
 * about.
 */
public class ThrottledLoginException extends RuntimeException {

    private final long retryAfterSeconds;

    public ThrottledLoginException(long retryAfterSeconds) {
        // Deliberately the same sentence for every caller and every reason: see
        // the class comment.
        super("Too many login attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return whole seconds until the limiter will answer again, at least 1 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
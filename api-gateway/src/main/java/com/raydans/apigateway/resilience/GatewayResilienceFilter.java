package com.raydans.apigateway.resilience;

import com.raydans.apigateway.web.ErrorResponseWriter;
import com.raydans.apigateway.web.GatewayExceptionHandler;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Load protection for the flash-sale route (#13): a rate limit and a bulkhead on
 * {@code POST /api/v1/reservations}, the one place an on-sale moment turns into a
 * spike orders of magnitude above normal load. The rate limit caps how many
 * reservations arrive; the bulkhead caps how many are in flight at once, so a
 * slow reservation-service cannot let them pile up and take every gateway thread.
 *
 * <p>And a circuit breaker on {@code /api/v1/payments/**}, the synchronous
 * payment-status lookup: while payment-service is failing, the route answers a
 * fast 503 instead of sending it more requests to fail. Nothing in the
 * reservation flow depends on this route, so degrading it costs a status page,
 * not a sale.
 *
 * <p>Runs after {@code JwtAuthenticationFilter}, so a request without a valid
 * token is refused there and never spends the budget. The budget is one per
 * gateway rather than one per caller: it caps what reservation-service is asked
 * to absorb, which is the thing being protected.
 *
 * <p>Every permission taken here is given back exactly once: completed when the
 * call it admitted ends, however it ends, or released when the request is
 * refused or the outcome says nothing about the service.
 */
public class GatewayResilienceFilter extends OncePerRequestFilter {

    /**
     * The bare collection and everything under it, for the same reason as the
     * event rule in {@code RoleAuthorizer}: the route proxies every spelling, so a
     * limit on only one would be a limit a trailing slash walks around. The same
     * pattern string as the reservation route's own predicate, which
     * {@code GatewayProxyBootTests} checks, so the two cannot drift apart.
     */
    public static final String RESERVATIONS_PATTERN = "/api/v1/reservations/**";

    /** As above, for the payment-service route. */
    public static final String PAYMENTS_PATTERN = "/api/v1/payments/**";

    private static final PathPattern RESERVATIONS = PathPatternParser.defaultInstance.parse(RESERVATIONS_PATTERN);
    private static final PathPattern PAYMENTS = PathPatternParser.defaultInstance.parse(PAYMENTS_PATTERN);

    private final RateLimiter reservationRateLimit;
    private final Bulkhead reservationBulkhead;
    private final CircuitBreaker paymentCircuit;
    private final ErrorResponseWriter errors;

    public GatewayResilienceFilter(
            RateLimiter reservationRateLimit,
            Bulkhead reservationBulkhead,
            CircuitBreaker paymentCircuit,
            ErrorResponseWriter errors) {
        this.reservationRateLimit = reservationRateLimit;
        this.reservationBulkhead = reservationBulkhead;
        this.paymentCircuit = paymentCircuit;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Parsed the way Spring MVC parses it for routing: the raw URI with the
        // context path removed, segments decoded and matrix variables dropped
        // for matching. A spelling the route matches is therefore a spelling this
        // matches, and the auth filter sees the same path within the application.
        PathContainer path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication();
        if (PAYMENTS.matches(path)) {
            throughPaymentCircuit(request, response, chain);
        } else if (HttpMethod.POST.matches(request.getMethod()) && RESERVATIONS.matches(path)) {
            throughReservationLimits(request, response, chain);
        } else {
            chain.doFilter(request, response);
        }
    }

    private void throughReservationLimits(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The bulkhead first, so a request it turns away has not spent a rate
        // permit: otherwise a slow service would fill the bulkhead, the requests
        // it refused would use up the minute's budget, and real reservations would
        // still be refused after the service recovered. A slot taken and then
        // refused a rate permit is given back without counting as a call.
        if (!reservationBulkhead.tryAcquirePermission()) {
            // No Retry-After: when a slot frees depends on how fast
            // reservation-service answers, which nothing here can say.
            errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many reservations in progress; try again shortly");
            return;
        }
        if (!reservationRateLimit.acquirePermission()) {
            reservationBulkhead.releasePermission();
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(secondsUntilTheNextRatePermit()));
            errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many reservation requests; try again shortly", headers);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            reservationBulkhead.onComplete();
        }
    }

    /**
     * When the limiter's next permit is due, rounded up so that a caller who
     * waits this long is not refused again for having come back early. At least
     * one second, because zero would read as "retry now".
     */
    private long secondsUntilTheNextRatePermit() {
        long nanos = reservationRateLimit instanceof AtomicRateLimiter atomic
                ? atomic.getDetailedMetrics().getNanosToWait()
                : reservationRateLimit.getRateLimiterConfig().getLimitRefreshPeriod().toNanos();
        long seconds = (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
        return Math.max(1, seconds);
    }

    private void throughPaymentCircuit(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!paymentCircuit.tryAcquirePermission()) {
            errors.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment status is temporarily unavailable; try again shortly");
            return;
        }
        long start = paymentCircuit.getCurrentTimestamp();
        boolean returned = false;
        try {
            chain.doFilter(request, response);
            returned = true;
        } finally {
            // One decision, made once, however the chain ended, so every
            // permission is recorded or released exactly once.
            settle(request, response, returned, paymentCircuit.getCurrentTimestamp() - start);
        }
    }

    private void settle(HttpServletRequest request, HttpServletResponse response, boolean returned, long elapsed) {
        if (request.getAttribute(GatewayExceptionHandler.HOP_FAILURE_ATTRIBUTE) != null) {
            // The hop to payment-service failed: refused, timed out, or cut off
            // part-way through its answer. Decided by the mark rather than the
            // status, because a hop that fails after the status was sent leaves
            // that status on the response, and its error page may not get written.
            paymentCircuit.onError(elapsed, paymentCircuit.getTimestampUnit(), new PaymentServiceFailure(502));
        } else if (!returned || request.getAttribute(GatewayExceptionHandler.GATEWAY_FAULT_ATTRIBUTE) != null) {
            // Not payment-service's doing: the gateway failing, or the caller
            // going away. Released rather than counted, or a gateway defect would
            // open the circuit and blame payment-service for it, and a half-open
            // circuit would wait for a trial call that never reports.
            paymentCircuit.releasePermission();
        } else if (response.getStatus() >= 500) {
            // payment-service answered with a 5xx.
            paymentCircuit.onError(elapsed, paymentCircuit.getTimestampUnit(),
                    new PaymentServiceFailure(response.getStatus()));
        } else {
            // A 4xx is payment-service answering (no payment for that reservation
            // yet, say), and counting it would open the circuit on ordinary lookups.
            paymentCircuit.onSuccess(elapsed, paymentCircuit.getTimestampUnit());
        }
    }

    /** What the breaker records for a failed call; the status is the service's, or 502 for a failed hop. */
    private static final class PaymentServiceFailure extends RuntimeException {
        PaymentServiceFailure(int status) {
            super("payment-service call failed with " + status, null, false, false);
        }
    }
}

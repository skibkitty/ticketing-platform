package com.raydans.apigateway.resilience;

import com.raydans.apigateway.web.ErrorResponseWriter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
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
 */
public class GatewayResilienceFilter extends OncePerRequestFilter {

    /**
     * The bare collection and everything under it, for the same reason as the
     * event rule in {@code RoleAuthorizer}: the route proxies every spelling, so a
     * limit on only one would be a limit a trailing slash walks around.
     */
    private static final PathPattern RESERVATIONS = PathPatternParser.defaultInstance.parse("/api/v1/reservations/**");
    private static final PathPattern PAYMENTS = PathPatternParser.defaultInstance.parse("/api/v1/payments/**");

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
        // The same path JwtAuthenticationFilter authorizes, so a context path
        // cannot put a request out of this filter's reach and still in the auth
        // filter's.
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
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
            refuseReservation(request, response);
            return;
        }
        if (!reservationRateLimit.acquirePermission()) {
            reservationBulkhead.releasePermission();
            refuseReservation(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            reservationBulkhead.onComplete();
        }
    }

    private void refuseReservation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many reservation requests; try again shortly");
    }

    private void throughPaymentCircuit(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!paymentCircuit.tryAcquirePermission()) {
            errors.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment status is temporarily unavailable; try again shortly");
            return;
        }
        long start = paymentCircuit.getCurrentTimestamp();
        boolean recorded = false;
        try {
            chain.doFilter(request, response);
            long elapsed = paymentCircuit.getCurrentTimestamp() - start;
            // A 5xx is the service struggling, or the gateway failing to reach it
            // or timing out, which GatewayExceptionHandler answers as one. A 4xx
            // is the service answering (no payment for that reservation yet, say),
            // and counting it would open the circuit on ordinary lookups.
            if (response.getStatus() >= 500) {
                paymentCircuit.onError(elapsed, paymentCircuit.getTimestampUnit(),
                        new PaymentServiceFailure(response.getStatus()));
            } else {
                paymentCircuit.onSuccess(elapsed, paymentCircuit.getTimestampUnit());
            }
            recorded = true;
        } finally {
            // Anything that escapes the chain is not payment-service's doing:
            // every downstream failure has already been turned into a response
            // above. What is left is the caller hanging up mid-response, or the
            // gateway itself failing, and neither is evidence about the service.
            // The permission still has to be given back, or a half-open circuit
            // waits for a trial call that will never report.
            if (!recorded) {
                paymentCircuit.releasePermission();
            }
        }
    }

    /** What the breaker records for a hop that came back 5xx rather than throwing. */
    private static final class PaymentServiceFailure extends RuntimeException {
        PaymentServiceFailure(int status) {
            super("payment-service answered " + status, null, false, false);
        }
    }
}

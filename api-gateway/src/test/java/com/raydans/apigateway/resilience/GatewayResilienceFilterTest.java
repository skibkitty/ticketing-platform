package com.raydans.apigateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.apigateway.web.ErrorResponseWriter;
import com.raydans.apigateway.web.GatewayExceptionHandler;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter's permit bookkeeping and path matching, against real resilience4j
 * instances rather than mocks: the claim is about what state the limiter,
 * bulkhead and breaker are left in, and a mock would only say which methods were
 * called. The boot tests cover the same filter in the real context; this covers
 * the exits a stand-in service cannot produce on demand, such as an exception
 * escaping the chain or a fault inside the gateway.
 */
class GatewayResilienceFilterTest {

    private static final int RATE = 3;
    private static final int IN_FLIGHT = 2;
    private static final int TRIALS = 2;

    private RateLimiter rateLimit;
    private Bulkhead bulkhead;
    private CircuitBreaker circuit;
    private GatewayResilienceFilter filter;

    @BeforeEach
    void freshProtections() {
        rateLimit = RateLimiter.of("reservations", RateLimiterConfig.custom()
                .limitForPeriod(RATE)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        bulkhead = Bulkhead.of("reservations", BulkheadConfig.custom()
                .maxConcurrentCalls(IN_FLIGHT)
                .maxWaitDuration(Duration.ZERO)
                .build());
        circuit = CircuitBreaker.of("payments", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .permittedNumberOfCallsInHalfOpenState(TRIALS)
                .build());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new GatewayResilienceFilter(rateLimit, bulkhead, circuit, new ErrorResponseWriter(json));
    }

    // --- which requests are protected ------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/reservations",
        "/api/v1/reservations/",
        "/api/v1/reservations/7/cancel",
        "/api/v1/%72eservations",
        "/api/v1/reservations;jsessionid=abc"
    })
    void everySpellingOfAReservationPostSpendsTheBudget(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        filter.doFilter(request, new MockHttpServletResponse(), answering(200));

        assertThat(rateLimit.getMetrics().getAvailablePermissions()).isEqualTo(RATE - 1);
    }

    @Test
    void aContextPathDoesNotTakeAReservationOutOfReach() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/gw/api/v1/reservations");
        request.setContextPath("/gw");
        filter.doFilter(request, new MockHttpServletResponse(), answering(200));

        assertThat(rateLimit.getMetrics().getAvailablePermissions()).isEqualTo(RATE - 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void onlyAPostSpendsTheReservationBudget(String method) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, "/api/v1/reservations"),
                new MockHttpServletResponse(), answering(200));

        assertThat(rateLimit.getMetrics().getAvailablePermissions()).isEqualTo(RATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/events", "/api/v1/reservationsx", "/api/v1/notifications", "/auth/login"})
    void otherRoutesSpendNothing(String uri) throws Exception {
        filter.doFilter(new MockHttpServletRequest("POST", uri), new MockHttpServletResponse(), answering(500));

        assertThat(rateLimit.getMetrics().getAvailablePermissions()).isEqualTo(RATE);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/payments/7", "/api/v1/payments", "/api/v1/payments/", "/api/v1/%70ayments/7"})
    void everySpellingOfAPaymentCallGoesThroughTheCircuit(String uri) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(), answering(200));

        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    // --- reservation permits ---------------------------------------------------

    @Test
    void aBulkheadRefusalSpendsNoRatePermit() throws Exception {
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(reservation(), response, answering(200));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(rateLimit.getMetrics().getAvailablePermissions()).isEqualTo(RATE);
    }

    @Test
    void aRateRefusalGivesItsBulkheadSlotBack() throws Exception {
        for (int i = 0; i < RATE; i++) {
            filter.doFilter(reservation(), new MockHttpServletResponse(), answering(200));
        }

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(reservation(), refused, answering(200));

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).matches("[1-9][0-9]*");
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isLessThanOrEqualTo(60);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(IN_FLIGHT);
    }

    @Test
    void aSlotIsGivenBackWhenTheServiceFails() throws Exception {
        filter.doFilter(reservation(), new MockHttpServletResponse(), answering(503));

        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(IN_FLIGHT);
    }

    @Test
    void aSlotIsGivenBackWhenTheChainThrows() {
        FilterChain throwing = (req, res) -> {
            throw new IOException("the caller hung up");
        };

        assertThatThrownBy(() -> filter.doFilter(reservation(), new MockHttpServletResponse(), throwing))
                .isInstanceOf(IOException.class);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(IN_FLIGHT);
    }

    @Test
    void aSlotIsGivenBackWhenTheChainThrowsAnError() {
        FilterChain throwing = (req, res) -> {
            throw new StackOverflowError();
        };

        assertThatThrownBy(() -> filter.doFilter(reservation(), new MockHttpServletResponse(), throwing))
                .isInstanceOf(StackOverflowError.class);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(IN_FLIGHT);
    }

    // --- circuit permits -------------------------------------------------------

    @Test
    void aFiveHundredFromTheServiceCountsAndAFourHundredDoesNot() throws Exception {
        filter.doFilter(payment(), new MockHttpServletResponse(), answering(404));
        filter.doFilter(payment(), new MockHttpServletResponse(), answering(502));

        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void aGatewayFaultIsNotCountedAgainstTheService() throws Exception {
        FilterChain gatewayFault = (req, res) -> {
            req.setAttribute(GatewayExceptionHandler.GATEWAY_FAULT_ATTRIBUTE, Boolean.TRUE);
            ((MockHttpServletResponse) res).setStatus(500);
        };

        for (int i = 0; i < 5; i++) {
            filter.doFilter(payment(), new MockHttpServletResponse(), gatewayFault);
        }

        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedHopCountsEvenWhenTheServicesStatusWasAlreadySent() throws Exception {
        // The body failed after a 200 was on the response, and the error page could
        // not replace it. The mark decides, not the status.
        FilterChain cutOffAfterTheStatus = (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(200);
            req.setAttribute(GatewayExceptionHandler.HOP_FAILURE_ATTRIBUTE, Boolean.TRUE);
        };

        filter.doFilter(payment(), new MockHttpServletResponse(), cutOffAfterTheStatus);

        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void aFailedHopCountsEvenWhenItsErrorPageThrows() {
        FilterChain cutOffThenThrows = (req, res) -> {
            req.setAttribute(GatewayExceptionHandler.HOP_FAILURE_ATTRIBUTE, Boolean.TRUE);
            throw new IOException("the error page could not be written");
        };

        assertThatThrownBy(() -> filter.doFilter(payment(), new MockHttpServletResponse(), cutOffThenThrows))
                .isInstanceOf(IOException.class);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void anOpenCircuitRefusesWithoutCallingTheChain() throws Exception {
        circuit.transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(payment(), response, (req, res) -> calls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(calls).hasValue(0);
    }

    @Test
    void aHalfOpenTrialThatThrowsGivesItsPermitBack() {
        // If it did not, the circuit would wait for a trial that never reports:
        // with every trial permit taken and none recorded, it would refuse every
        // payment call for good.
        circuit.transitionToOpenState();
        circuit.transitionToHalfOpenState();
        FilterChain throwing = (req, res) -> {
            throw new IOException("the caller hung up");
        };

        for (int i = 0; i < TRIALS; i++) {
            assertThatThrownBy(() -> filter.doFilter(payment(), new MockHttpServletResponse(), throwing))
                    .isInstanceOf(IOException.class);
        }

        assertThat(circuit.tryAcquirePermission()).isTrue();
        circuit.releasePermission();
    }

    @Test
    void aHalfOpenTrialThatHitsAGatewayFaultGivesItsPermitBack() throws Exception {
        circuit.transitionToOpenState();
        circuit.transitionToHalfOpenState();
        FilterChain gatewayFault = (req, res) -> {
            req.setAttribute(GatewayExceptionHandler.GATEWAY_FAULT_ATTRIBUTE, Boolean.TRUE);
            ((MockHttpServletResponse) res).setStatus(500);
        };

        for (int i = 0; i < TRIALS * 2; i++) {
            filter.doFilter(payment(), new MockHttpServletResponse(), gatewayFault);
        }

        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(circuit.tryAcquirePermission()).isTrue();
        circuit.releasePermission();
    }

    @Test
    void eachCallIsRecordedExactlyOnce() throws Exception {
        circuit.transitionToOpenState();
        circuit.transitionToHalfOpenState();

        for (int i = 0; i < TRIALS; i++) {
            filter.doFilter(payment(), new MockHttpServletResponse(), answering(200));
        }

        // TRIALS successes and nothing else close it; a double count would have
        // closed it after half as many, and a missing one would leave it half open.
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private static MockHttpServletRequest reservation() {
        return new MockHttpServletRequest("POST", "/api/v1/reservations");
    }

    private static MockHttpServletRequest payment() {
        return new MockHttpServletRequest("GET", "/api/v1/payments/7");
    }

    private static FilterChain answering(int status) {
        return (req, res) -> ((MockHttpServletResponse) res).setStatus(status);
    }
}

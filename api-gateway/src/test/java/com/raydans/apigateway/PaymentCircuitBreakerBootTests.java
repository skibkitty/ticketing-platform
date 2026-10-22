package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.raydans.common.web.CorrelationIdFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The circuit breaker on the payment-status route, over HTTP (#13): a struggling
 * payment-service degrades {@code /api/v1/payments/**} to a fast 503 instead of a
 * hanging request, and stops being sent requests it is failing.
 *
 * <p>The breaker is configured through the same environment variables a
 * deployment would set, with numbers small enough that "open" is a couple of
 * requests. Each test asserts both what the caller got and what the breaker and
 * the service saw, because a 503 alone cannot tell an open circuit from a
 * service that answered 503 itself. Half-open is entered through the breaker's
 * own transition rather than by waiting, so no test depends on a sleep being
 * long enough. The breaker lives in the application context, so every test gets
 * a fresh one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentCircuitBreakerBootTests {

    /** Calls the breaker sees before it judges; all of them failing opens it. */
    private static final int CALLS_TO_JUDGE = 2;
    private static final int TRIAL_CALLS = 2;
    private static final Duration OPEN_FOR = Duration.ofSeconds(1);
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";
    private static final long CUSTOMER_ID = 42L;
    private static final String CORRELATION_ID = "0af7651916cd43dd8448eb211c80319c";

    private static StandInService paymentService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @Autowired
    CircuitBreakerRegistry circuits;

    @BeforeAll
    static void startTheStandInService() throws IOException {
        paymentService = StandInService.start();
    }

    @AfterAll
    static void stopTheStandInService() {
        paymentService.stop();
    }

    @DynamicPropertySource
    static void aDeploymentWithATwitchyBreaker(DynamicPropertyRegistry registry) {
        registry.add("PAYMENT_SERVICE_URL", paymentService::url);
        registry.add("PAYMENTS_CIRCUIT_WINDOW_SIZE", () -> CALLS_TO_JUDGE);
        registry.add("PAYMENTS_CIRCUIT_MIN_CALLS", () -> CALLS_TO_JUDGE);
        registry.add("PAYMENTS_CIRCUIT_TRIAL_CALLS", () -> TRIAL_CALLS);
        registry.add("PAYMENTS_CIRCUIT_OPEN_FOR", OPEN_FOR::toString);
        registry.add("GATEWAY_READ_TIMEOUT", () -> "PT0.5S");
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @BeforeEach
    void aHealthyService() {
        paymentService.reset();
    }

    // --- opening ---------------------------------------------------------------

    @Test
    void theCircuitOpensAtTheThresholdAndThenAnswers503WithoutCallingTheService() throws Exception {
        paymentService.answerWith(500);
        for (int i = 0; i < CALLS_TO_JUDGE - 1; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        }
        assertThat(circuit().getState()).as("one call short of the minimum").isEqualTo(CircuitBreaker.State.CLOSED);

        mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        mvc.perform(paymentStatus().header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Payment status is temporarily unavailable; try again shortly"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/7"));

        assertThat(paymentService.received()).hasSize(CALLS_TO_JUDGE);
    }

    @Test
    void aServiceThatHangsOpensTheCircuitToo() throws Exception {
        // The failure a circuit breaker is most for: a service that accepts the
        // connection and never answers. Without a read timeout the hop would never
        // end, so it would never count, and the circuit would never open.
        paymentService.hold(CALLS_TO_JUDGE);
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(paymentStatus())
                    .andExpect(status().isInternalServerError())
                    // The gateway's own message, not the I/O error's, which names
                    // the internal service address.
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());
        assertThat(paymentService.received()).hasSize(CALLS_TO_JUDGE);
    }

    @Test
    void aServiceThatStallsPartWayThroughItsAnswerOpensTheCircuitToo() throws Exception {
        // The body is copied after RestClient has handed back the status, so a hop
        // that fails here fails as a raw I/O error rather than as the
        // ResourceAccessException a refused or silent service produces. It is
        // still payment-service failing, and has to count as that.
        paymentService.stallMidBody();
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(paymentStatus());
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());
        assertThat(paymentService.received()).hasSize(CALLS_TO_JUDGE);
    }

    @Test
    void anAnswerTheServiceMeantIsNotAFailure() throws Exception {
        // A 404 for a reservation with no payment yet is payment-service working,
        // not struggling. Counting it would open the circuit on ordinary lookups.
        paymentService.answerWith(404);
        for (int i = 0; i < CALLS_TO_JUDGE * 3; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isNotFound());
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(paymentService.received()).hasSize(CALLS_TO_JUDGE * 3);
    }

    @Test
    void anUnauthenticatedCallerNeverReachesTheCircuit() throws Exception {
        // Refused by authentication first, so a flood with no token can neither
        // open the circuit nor take a half-open circuit's trial calls.
        circuit().transitionToOpenState();
        circuit().transitionToHalfOpenState();
        for (int i = 0; i < TRIAL_CALLS * 3; i++) {
            mvc.perform(get("/api/v1/payments/7")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(circuit().getMetrics().getNumberOfBufferedCalls()).isZero();
        for (int i = 0; i < TRIAL_CALLS; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isOk());
        }
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // --- recovering ------------------------------------------------------------

    @Test
    void successfulTrialCallsCloseTheCircuit() throws Exception {
        circuit().transitionToOpenState();
        circuit().transitionToHalfOpenState();

        for (int i = 0; i < TRIAL_CALLS; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isOk());
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(paymentService.received()).hasSize(TRIAL_CALLS);
    }

    @Test
    void failedTrialCallsOpenTheCircuitAgain() throws Exception {
        circuit().transitionToOpenState();
        circuit().transitionToHalfOpenState();
        paymentService.answerWith(500);

        for (int i = 0; i < TRIAL_CALLS; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());
        assertThat(paymentService.received()).hasSize(TRIAL_CALLS);
    }

    @Test
    void theCircuitAdmitsATrialOnceItHasBeenOpenForTheConfiguredTime() throws Exception {
        // The one test that runs on the clock, because the claim is about the
        // configured duration. It polls for the trial against a deadline rather
        // than sleeping a fixed time, and asserts the circuit refused until then.
        circuit().transitionToOpenState();
        long opened = System.nanoTime();
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());

        int answered;
        long deadline = opened + OPEN_FOR.multipliedBy(5).toNanos();
        do {
            answered = mvc.perform(paymentStatus()).andReturn().getResponse().getStatus();
            if (answered == 503) {
                Thread.onSpinWait();
            }
        } while (answered == 503 && System.nanoTime() < deadline);

        assertThat(answered).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - opened)).isGreaterThanOrEqualTo(OPEN_FOR);
        assertThat(paymentService.received()).hasSize(1);
    }

    @Test
    void otherRoutesAreNotAffectedByAnOpenPaymentCircuit() throws Exception {
        circuit().transitionToOpenState();

        // The reservation route points nowhere in this class, so it is enough that
        // it is not answered with the payment route's 503.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(503));
    }

    private CircuitBreaker circuit() {
        return circuits.circuitBreaker("payments");
    }

    private MockHttpServletRequestBuilder paymentStatus() {
        return get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer());
    }

    private String bearer() {
        return "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token();
    }
}

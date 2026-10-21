package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import java.io.IOException;
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
 * requests and "try again" is a second. The breaker's state lives in the
 * application context, so every test gets a fresh one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentCircuitBreakerBootTests {

    /** Calls the breaker sees before it judges; all of them failing opens it. */
    private static final int CALLS_TO_JUDGE = 2;
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";
    private static final long CUSTOMER_ID = 42L;

    private static StandInService paymentService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

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
        registry.add("PAYMENTS_CIRCUIT_OPEN_FOR", () -> "PT1S");
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

    @Test
    void anOpenCircuitAnswers503WithoutCallingTheService() throws Exception {
        paymentService.answerWith(500);
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        }

        mvc.perform(paymentStatus())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
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
            mvc.perform(paymentStatus()).andExpect(status().is5xxServerError());
        }

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

        assertThat(paymentService.received()).hasSize(CALLS_TO_JUDGE * 3);
    }

    @Test
    void theCircuitLetsATrialRequestThroughOnceItHasBeenOpenLongEnough() throws Exception {
        paymentService.answerWith(500);
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        }
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());

        paymentService.answerWith(200);
        Thread.sleep(1_500);

        mvc.perform(paymentStatus()).andExpect(status().isOk());
    }

    @Test
    void otherRoutesAreNotAffectedByAnOpenPaymentCircuit() throws Exception {
        // The reservation route points nowhere in this class, so it is enough that
        // it is not answered with the payment route's 503.
        paymentService.answerWith(500);
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(paymentStatus()).andExpect(status().isInternalServerError());
        }
        mvc.perform(paymentStatus()).andExpect(status().isServiceUnavailable());

        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(503));
    }

    private MockHttpServletRequestBuilder paymentStatus() {
        return get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer());
    }

    private String bearer() {
        return "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token();
    }
}

package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The gateway's connection pool under contention, through the real client the
 * proxy uses (#13).
 *
 * <p>A service that holds every connection the gateway may open to it must not
 * make the next request to it wait for one indefinitely, and must not take
 * connections from a different service. The pool is two per service here, so
 * "every connection" is two held requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayConnectionPoolBootTests {

    private static final int PER_SERVICE = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";

    private static StandInService reservationService;
    private static StandInService paymentService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @BeforeAll
    static void startTheStandInServices() throws IOException {
        reservationService = StandInService.start();
        paymentService = StandInService.start();
    }

    @AfterAll
    static void stopTheStandInServices() {
        reservationService.stop();
        paymentService.stop();
    }

    @DynamicPropertySource
    static void aDeploymentWithASmallPool(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", reservationService::url);
        registry.add("PAYMENT_SERVICE_URL", paymentService::url);
        registry.add("GATEWAY_MAX_CONNECTIONS_PER_SERVICE", () -> PER_SERVICE);
        // The pool may not be smaller than the bulkhead, so the bulkhead comes down
        // with it. Event reads are what fill the pool here, and they are not
        // bulkheaded at all.
        registry.add("RESERVATION_BULKHEAD_MAX_CONCURRENT", () -> PER_SERVICE);
        registry.add("GATEWAY_CONNECT_TIMEOUT", CONNECT_TIMEOUT::toString);
        registry.add("GATEWAY_READ_TIMEOUT", () -> "PT8S");
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @Test
    void aSaturatedServiceFailsTheNextRequestPromptlyLeavesOthersAloneAndRecovers() throws Exception {
        reservationService.reset();
        paymentService.reset();
        CountDownLatch held = reservationService.hold(PER_SERVICE);
        ExecutorService callers = Executors.newFixedThreadPool(PER_SERVICE);
        try {
            List<Future<Integer>> holding = new ArrayList<>();
            for (int i = 0; i < PER_SERVICE; i++) {
                holding.add(callers.submit(() -> mvc.perform(events()).andReturn().getResponse().getStatus()));
            }
            assertThat(held.await(10, TimeUnit.SECONDS)).as("every pooled connection is in use").isTrue();

            // The next request to the same service has no connection to use. It is
            // answered with the gateway's controlled failure within the time it
            // allows for getting a connection, rather than parked until one frees.
            long started = System.nanoTime();
            mvc.perform(events()).andExpect(status().isInternalServerError());
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isLessThan(CONNECT_TIMEOUT.multipliedBy(3));

            // A different service has a pool of its own.
            mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            reservationService.release();
            for (Future<Integer> call : holding) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
            mvc.perform(events()).andExpect(status().isOk());
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder events() {
        return get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearer());
    }

    private String bearer() {
        return "Bearer " + tokens.issue(42L, Set.of(Role.CUSTOMER)).token();
    }
}

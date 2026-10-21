package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.io.IOException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The bulkhead on the flash-sale route, over HTTP (#13): how many reservations
 * may be in flight to reservation-service at once, so a slow service cannot let
 * them pile up and take every gateway thread with them.
 *
 * <p>The stand-in holds requests open on demand, which is the slow service, and
 * records the most it ever had in progress at once, which is the number the
 * bulkhead exists to bound. Concurrency is arranged with latches, never sleeps.
 * The cap and the rate limit come from their environment variables, as a
 * deployment's would; the rate limit is set low enough that a test can see
 * whether a refusal spent it. Each test gets a fresh context, because both the
 * rate budget and the bulkhead live in it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReservationBulkheadBootTests {

    /**
     * Above the 5 connections per service an HTTP client pool commonly allows by
     * default, so this also checks that the gateway's connection pool admits as
     * many hops as the bulkhead does: otherwise the bulkhead would admit requests
     * that then queue inside the gateway for a connection, which is the pile-up it
     * exists to prevent.
     */
    private static final int MAX_IN_FLIGHT = 8;
    private static final int RATE_LIMIT = 40;
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";
    private static final long CUSTOMER_ID = 42L;

    private static StandInService reservationService;
    private static StandInService paymentService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @Autowired
    BulkheadRegistry bulkheads;

    @Autowired
    RateLimiterRegistry rateLimits;

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
    static void aDeploymentWithASmallBulkhead(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", reservationService::url);
        registry.add("PAYMENT_SERVICE_URL", paymentService::url);
        registry.add("RESERVATION_BULKHEAD_MAX_CONCURRENT", () -> MAX_IN_FLIGHT);
        registry.add("RESERVATION_RATE_LIMIT", () -> RATE_LIMIT);
        // Long enough that a held request is still held when a test looks at it,
        // short enough that the timeout test does not take long.
        registry.add("GATEWAY_READ_TIMEOUT", () -> "PT2S");
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @BeforeEach
    void aHealthyService() {
        reservationService.reset();
        paymentService.reset();
    }

    @Test
    void aBurstNeverPutsMoreThanTheCapInFlightAndTheRestAreRefusedAtOnce() throws Exception {
        int burst = MAX_IN_FLIGHT * 3;
        CountDownLatch admitted = reservationService.hold(MAX_IN_FLIGHT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch refused = new CountDownLatch(burst - MAX_IN_FLIGHT);
        ExecutorService callers = Executors.newFixedThreadPool(burst);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                calls.add(callers.submit(() -> {
                    start.await();
                    int answered = mvc.perform(reserve()).andReturn().getResponse().getStatus();
                    if (answered == 429) {
                        refused.countDown();
                    }
                    return answered;
                }));
            }
            start.countDown();

            // Everything past the cap is refused while the admitted ones are still
            // held at the service, and well inside the read timeout: a refusal does
            // not wait for a slot.
            assertThat(admitted.await(10, TimeUnit.SECONDS)).as("the cap's worth reach the service").isTrue();
            assertThat(refused.await(1, TimeUnit.SECONDS)).as("the rest are refused at once").isTrue();
            assertThat(calls.stream().filter(call -> !call.isDone()).count())
                    .as("the admitted ones are still in flight")
                    .isEqualTo(MAX_IN_FLIGHT);

            reservationService.release();
            List<Integer> answers = new ArrayList<>();
            for (Future<Integer> call : calls) {
                answers.add(call.get(10, TimeUnit.SECONDS));
            }
            assertThat(answers).filteredOn(answer -> answer == 200).hasSize(MAX_IN_FLIGHT);
            assertThat(answers).filteredOn(answer -> answer == 429).hasSize(burst - MAX_IN_FLIGHT);
            assertThat(reservationService.mostInFlight()).isEqualTo(MAX_IN_FLIGHT);
            assertThat(reservationService.received()).hasSize(MAX_IN_FLIGHT);
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    @Test
    void aRefusedReservationSucceedsOnceASlotIsFreeAndRefusalsSpendNoRateBudget() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(MAX_IN_FLIGHT);
        try {
            List<Future<Integer>> held = fillTheBulkhead(callers);

            int refused = RATE_LIMIT;
            for (int i = 0; i < refused; i++) {
                mvc.perform(reserve())
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.status").value(429))
                        .andExpect(jsonPath("$.path").value("/api/v1/reservations"))
                        // Only the rate limit can say when to come back.
                        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
            }
            // As many refusals as the whole budget, and the budget is down only by
            // the reservations that were admitted.
            assertThat(rateLimits.rateLimiter("reservations").getMetrics().getAvailablePermissions())
                    .isEqualTo(RATE_LIMIT - MAX_IN_FLIGHT);

            reservationService.release();
            for (Future<Integer> call : held) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
            mvc.perform(reserve()).andExpect(status().isOk());
            assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(MAX_IN_FLIGHT);
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    @Test
    void aSlotIsGivenBackWhenAReservationCompletes() throws Exception {
        // Sequential calls never overlap, so a cap that leaked a slot per call
        // would refuse the one after MAX_IN_FLIGHT.
        for (int i = 0; i < MAX_IN_FLIGHT * 3; i++) {
            mvc.perform(reserve()).andExpect(status().isOk());
        }
    }

    @Test
    void aSlotIsGivenBackWhenTheServiceFails() throws Exception {
        reservationService.answerWith(503);
        for (int i = 0; i < MAX_IN_FLIGHT * 2; i++) {
            mvc.perform(reserve()).andExpect(status().isServiceUnavailable());
        }

        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(MAX_IN_FLIGHT);
    }

    @Test
    void slotsHeldByAServiceThatHangsAreGivenBackWhenTheHopTimesOut() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(MAX_IN_FLIGHT);
        try {
            List<Future<Integer>> held = fillTheBulkhead(callers);
            mvc.perform(reserve()).andExpect(status().isTooManyRequests());

            // The stand-in keeps holding; the gateway's read timeout ends each hop.
            for (Future<Integer> call : held) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(500);
            }

            assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(MAX_IN_FLIGHT);
            reservationService.release();
            mvc.perform(reserve()).andExpect(status().isOk());
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    @Test
    void aFullBulkheadLeavesOtherRoutesAlone() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(MAX_IN_FLIGHT);
        try {
            fillTheBulkhead(callers);
            mvc.perform(reserve()).andExpect(status().isTooManyRequests());

            mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());
            assertThat(paymentService.received()).containsExactly("/api/v1/payments/7");
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    /** Puts MAX_IN_FLIGHT reservations in flight, held at the service, and returns them. */
    private List<Future<Integer>> fillTheBulkhead(ExecutorService callers) throws InterruptedException {
        CountDownLatch inFlight = reservationService.hold(MAX_IN_FLIGHT);
        List<Future<Integer>> held = new ArrayList<>();
        for (int i = 0; i < MAX_IN_FLIGHT; i++) {
            held.add(callers.submit(() -> mvc.perform(reserve()).andReturn().getResponse().getStatus()));
        }
        assertThat(inFlight.await(10, TimeUnit.SECONDS)).as("the bulkhead is full").isTrue();
        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isZero();
        return held;
    }

    private Bulkhead bulkhead() {
        return bulkheads.bulkhead("reservations");
    }

    private String bearer() {
        return "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token();
    }

    private MockHttpServletRequestBuilder reserve() {
        return post("/api/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatIds\": [1]}");
    }
}

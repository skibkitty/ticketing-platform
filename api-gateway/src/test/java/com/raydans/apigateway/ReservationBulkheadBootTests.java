package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The bulkhead on the flash-sale route, over HTTP (#13): how many reservations
 * may be in flight to reservation-service at once, so a slow service cannot let
 * them pile up and take every gateway thread with them.
 *
 * <p>The stand-in holds requests open on demand, which is the slow service. The
 * cap comes from {@code RESERVATION_BULKHEAD_MAX_CONCURRENT}, as a deployment's
 * would.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationBulkheadBootTests {

    /**
     * Above the 5 connections per service an HTTP client pool commonly allows by
     * default, so this also checks that the gateway's connection pool admits as
     * many hops as the bulkhead does: otherwise the bulkhead would admit requests
     * that then queue inside the gateway for a connection, which is the pile-up it
     * exists to prevent.
     */
    private static final int MAX_IN_FLIGHT = 8;
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";
    private static final long CUSTOMER_ID = 42L;

    private static StandInService reservationService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @BeforeAll
    static void startTheStandInService() throws IOException {
        reservationService = StandInService.start();
    }

    @AfterAll
    static void stopTheStandInService() {
        reservationService.stop();
    }

    @DynamicPropertySource
    static void aDeploymentWithASmallBulkhead(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", reservationService::url);
        registry.add("RESERVATION_BULKHEAD_MAX_CONCURRENT", () -> MAX_IN_FLIGHT);
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @Test
    void aReservationPastTheInFlightCapIsRefusedWith429AndTheOthersStillComplete() throws Exception {
        reservationService.reset();
        CountDownLatch inFlight = reservationService.hold(MAX_IN_FLIGHT);
        ExecutorService callers = Executors.newFixedThreadPool(MAX_IN_FLIGHT);
        try {
            List<Future<Integer>> held = new ArrayList<>();
            for (int i = 0; i < MAX_IN_FLIGHT; i++) {
                held.add(callers.submit(() -> mvc.perform(reserve()).andReturn().getResponse().getStatus()));
            }
            assertThat(inFlight.await(10, TimeUnit.SECONDS))
                    .as("the held reservations reach the service")
                    .isTrue();

            mvc.perform(reserve())
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.path").value("/api/v1/reservations"));

            reservationService.release();
            for (Future<Integer> call : held) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
            assertThat(reservationService.received()).hasSize(MAX_IN_FLIGHT);
        } finally {
            reservationService.release();
            callers.shutdownNow();
        }
    }

    @Test
    void aSlotIsGivenBackWhenAReservationCompletes() throws Exception {
        // Sequential calls never overlap, so a cap that leaked a slot per call
        // would refuse the one after MAX_IN_FLIGHT.
        reservationService.reset();
        for (int i = 0; i < MAX_IN_FLIGHT * 3; i++) {
            mvc.perform(reserve()).andExpect(status().isOk());
        }
    }

    private MockHttpServletRequestBuilder reserve() {
        return post("/api/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatIds\": [1]}");
    }
}

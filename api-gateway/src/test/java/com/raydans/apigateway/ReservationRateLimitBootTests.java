package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * The rate limit on the flash-sale route, over HTTP (#13).
 *
 * <p>The limit is supplied the way a deployment supplies it, through the
 * {@code RESERVATION_RATE_LIMIT} environment variable, so these tests are also the
 * claim that the environment changes the limit without a rebuild. It is set far
 * below the shipped default so that "past the limit" is a handful of requests.
 *
 * <p>The budget is one per gateway and lives in the application context, so every
 * test gets a fresh context: a test that spent the budget would otherwise leave
 * the next one refused, in an order no test controls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReservationRateLimitBootTests {

    private static final int LIMIT = 3;
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
    static void aDeploymentWithASmallLimit(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", reservationService::url);
        registry.add("RESERVATION_RATE_LIMIT", () -> LIMIT);
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @Test
    void reservationsPastTheLimitAreRefusedWith429AndNeverReachTheService() throws Exception {
        reservationService.reset();
        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(reserve()).andExpect(status().isOk());
        }

        mvc.perform(reserve())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));

        assertThat(reservationService.received()).hasSize(LIMIT);
    }

    @Test
    void anUnauthenticatedFloodDoesNotSpendTheBudget() throws Exception {
        // Refused by authentication first, so a flood with no token cannot lock
        // real Customers out of the on-sale moment.
        for (int i = 0; i < LIMIT * 3; i++) {
            mvc.perform(post("/api/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                    .andExpect(status().isUnauthorized());
        }

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(reserve()).andExpect(status().isOk());
        }
    }

    @Test
    void browsingEventsIsNotLimited() throws Exception {
        for (int i = 0; i < LIMIT * 3; i++) {
            mvc.perform(get("/api/v1/events")
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());
        }
    }

    private String bearer() {
        return "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token();
    }

    private MockHttpServletRequestBuilder reserve() {
        return post("/api/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatIds\": [1]}");
    }
}

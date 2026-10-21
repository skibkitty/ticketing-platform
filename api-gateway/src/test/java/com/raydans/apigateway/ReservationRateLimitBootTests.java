package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.raydans.common.web.CorrelationIdFilter;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
    private static final String CORRELATION_ID = "0af7651916cd43dd8448eb211c80319c";

    private static StandInService reservationService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @Autowired
    RateLimiterRegistry rateLimits;

    @Autowired
    BulkheadRegistry bulkheads;

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
    void aRefusalCarriesTheCorrelationIdAndARetryAfter() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(reserve()).andExpect(status().isOk());
        }

        mvc.perform(reserve().header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                // The rest of the limiter's refresh period, so it is never more
                // than the configured period and never less than a second.
                .andExpect(header().string(HttpHeaders.RETRY_AFTER,
                        org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
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
    void aBadOrRefusedTokenDoesNotSpendTheBudgetEither() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(reserveWith("Bearer not.a.token")).andExpect(status().isUnauthorized());
            mvc.perform(reserveWith(expiredToken())).andExpect(status().isUnauthorized());
            // Authenticated, but not a Customer, so refused by role before the
            // limit is asked.
            mvc.perform(reserveWith("Bearer " + tokens.issue(43L, Set.of(Role.ORGANIZER)).token()))
                    .andExpect(status().isForbidden());
        }

        assertThat(rateLimits.rateLimiter("reservations").getMetrics().getAvailablePermissions()).isEqualTo(LIMIT);
        assertThat(bulkheads.bulkhead("reservations").getMetrics().getAvailableConcurrentCalls())
                .isEqualTo(bulkheads.bulkhead("reservations").getBulkheadConfig().getMaxConcurrentCalls());
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
        return reserveWith("Bearer " + tokens.issue(CUSTOMER_ID, Set.of(Role.CUSTOMER)).token());
    }

    /** Correctly signed and a Customer's, but an hour past its expiry. */
    private static String expiredToken() {
        Instant anHourAgo = Instant.now().minus(Duration.ofHours(1));
        return "Bearer " + Jwts.builder()
                .subject(Long.toString(CUSTOMER_ID))
                .claim(JwtService.ROLES_CLAIM, Role.namesOf(Set.of(Role.CUSTOMER)))
                .issuedAt(Date.from(anHourAgo.minus(Duration.ofHours(1))))
                .expiration(Date.from(anHourAgo))
                .signWith(Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private MockHttpServletRequestBuilder reserveWith(String authorization) {
        return post("/api/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"seatIds\": [1]}");
    }
}

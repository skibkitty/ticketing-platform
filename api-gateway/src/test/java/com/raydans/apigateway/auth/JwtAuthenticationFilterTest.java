package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.apigateway.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The auth boundary as a caller experiences it: over HTTP, with real tokens,
 * asserting the status and the body shape and nothing about how the filter is
 * written.
 *
 * <p>{@code JwtAuthenticationFilter} is wired in by hand rather than through the
 * application context so that a failure here is the filter's, not the wiring's
 * — {@code GatewayBootTests} covers the wiring.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final JwtService tokens = new JwtService(
            new JwtProperties(SECRET, TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvcUsing(tokens);
    }

    /**
     * The same gateway, with the token service's clock as a parameter.
     *
     * <p>Needed because expiry is a property of the service doing the
     * verifying, not of the token: to watch a token expire from the gateway's
     * side the gateway has to be holding a clock that has moved past it.
     */
    private MockMvc mvcUsing(JwtService verifier) {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        ErrorResponseWriter errors = new ErrorResponseWriter(objectMapper);
        return MockMvcBuilders.standaloneSetup(new StandInForTheProxiedServices())
                .addFilters(new JwtAuthenticationFilter(verifier, new RoleAuthorizer(), errors))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // --- 401: no usable token -------------------------------------------------

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/v1/events"));
    }

    @Test
    void aRequestWithAnInventedTokenIsRefused() throws Exception {
        // Not merely "a token this gateway did not issue" — a string that is not
        // a token at all, which is what an unauthenticated scanner sends.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aNonBearerAuthorizationHeaderIsRefused() throws Exception {
        // A valid token sent in the wrong scheme is not a valid token: accepting
        // it would make "did you send it correctly" indistinguishable from "were
        // you allowed to".
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, tokens.issue("admin", Set.of(Role.ADMIN)).token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() throws Exception {
        JwtService someoneElse = new JwtService(
                new JwtProperties("a-different-secret-that-is-also-long-enough", TTL), Clock.fixed(NOW, ZoneOffset.UTC));

        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(someoneElse.issue("admin", Set.of(Role.ADMIN)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        String token = tokens.issue("admin", Set.of(Role.ADMIN)).token();
        MockMvc afterExpiry = mvcUsing(new JwtService(
                new JwtProperties(SECRET, TTL), Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC)));

        afterExpiry
                .perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theRefusalDoesNotSayWhyTheTokenWasRejected() throws Exception {
        // An expired token and a forged one are different problems, and the
        // client can do nothing useful with the difference.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.forged"))
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("expired"))))
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("signature"))));
    }

    // --- 403: authenticated, but not allowed ---------------------------------

    @Test
    void aCustomerMayNotCreateAnEvent() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("customer", Set.of(Role.CUSTOMER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void theRefusalNamesTheRolesThatWouldBeAccepted() throws Exception {
        // So a caller denied an action learns what to log in as, without having
        // to guess which of the three roles is the one that works.
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("customer", Set.of(Role.CUSTOMER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("ORGANIZER")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("ADMIN")));
    }

    @Test
    void anOrganizerMayCreateAnEvent() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("organizer", Set.of(Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminMayCreateAnEvent() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("admin", Set.of(Role.ADMIN))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void holdingOneRoleIsNotHoldingTheOthers() throws Exception {
        // The failure mode a containsAny check would let through: an organizer
        // token is not an admin token.
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("organizer", Set.of(Role.ORGANIZER)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerMayNotReachTheManagementEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("customer", Set.of(Role.CUSTOMER)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminMayReachTheManagementEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("admin", Set.of(Role.ADMIN)))))
                .andExpect(status().isOk());
    }

    @Test
    void theManagementEndpointsNeedATokenToo() throws Exception {
        // Gated, not open: ADMIN-only is a stronger condition than "no token",
        // and a request with no token must not be treated as satisfying it.
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    }

    // --- what an authorized caller gets --------------------------------------

    @Test
    void anyAuthenticatedRoleMayHoldSeats() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("organizer", Set.of(Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void anAuthorizedRequestReachesTheHandlerWithTheCallerOnIt() throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("customer", Set.of(Role.CUSTOMER)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("customer"));
    }

    @Test
    void aCallerHoldingSeveralRolesIsNotRefused() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                bearerFor(tokens.issue("root", Set.of(Role.CUSTOMER, Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // --- paths the filter must stay out of -----------------------------------

    @Test
    void loginIsReachableWithoutAToken() throws Exception {
        // 404 rather than 401 is the point: it proves the request got past the
        // filter and on to routing, where it found no GET handler. If the filter
        // had refused it, the platform would have no way in at all and every
        // client would be locked out rather than merely refused.
        mvc.perform(get("/auth/login")).andExpect(status().isNotFound());
    }

    @Test
    void aCorsPreflightIsNotRefused() {
        // Checked against the filter alone rather than through MockMvc: a
        // preflight is handled by the DispatcherServlet's own CORS machinery,
        // which has no handler adapter in a standalone setup and would fail for
        // reasons that have nothing to do with the filter. What this test is
        // actually about is whether the filter short-circuited, so it asks the
        // filter directly.
        //
        // The browser sends this before it has a token, and it performs no
        // action, so a 401 here reads to a client as a broken CORS setup.
        assertThat(preflightReachesTheRestOfTheChain("/api/v1/reservations")).isTrue();
    }

    @Test
    void aCorsPreflightOnTheManagementEndpointsIsAlsoNotRefused() {
        assertThat(preflightReachesTheRestOfTheChain("/actuator/health")).isTrue();
    }

    /** @return whether the filter passed the request on rather than answering it itself. */
    private boolean preflightReachesTheRestOfTheChain(String path) {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                tokens, new RoleAuthorizer(), new ErrorResponseWriter(objectMapper));

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedTheChain = new AtomicBoolean();
        FilterChain chain = (req, res) -> reachedTheChain.set(true);

        try {
            filter.doFilter(request, response, chain);
        } catch (IOException | ServletException ex) {
            throw new AssertionError("The filter should pass a preflight straight through", ex);
        }

        return reachedTheChain.get() && response.getStatus() != HttpStatus.UNAUTHORIZED.value();
    }

    // --- the header the gateway must not take from a client -------------------

    @Test
    void aClientSuppliedRoleHeaderDoesNotAuthenticateAnyone() throws Exception {
        // The trust boundary in one test. Downstream services read
        // X-User-Roles and have no authentication of their own (ADR 002), so if
        // a client could arrive already holding one, the gateway would be
        // decorative. This asserts the *filter* half: no token, no access, no
        // matter what the client claims to be.
        mvc.perform(get("/api/v1/events").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aClientSuppliedRoleHeaderDoesNotLiftARoleRefusal() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue("customer", Set.of(Role.CUSTOMER))))
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private static String bearerFor(IssuedToken token) {
        return "Bearer " + token.token();
    }

    /**
     * Stands in for the services behind the gateway. It exists so a permitted
     * request has somewhere to land: without a handler, a 200 would prove
     * nothing, because MockMvc answers an unrouted request with a 404 that is
     * indistinguishable from the gateway having refused.
     *
     * <p>Each handler echoes back the caller the filter authenticated, so the
     * tests check that a request not only got through but arrived as the right
     * person.
     */
    @RestController
    static class StandInForTheProxiedServices {

        @GetMapping("/api/v1/events")
        Map<String, Object> listEvents(HttpServletRequest request) {
            return Map.of("username", usernameOf(request), "ok", true);
        }

        @PostMapping("/api/v1/events")
        Map<String, Object> createEvent(HttpServletRequest request) {
            return Map.of("username", usernameOf(request), "ok", true);
        }

        @PostMapping("/api/v1/reservations")
        Map<String, Object> createReservation(HttpServletRequest request) {
            return Map.of("username", usernameOf(request), "ok", true);
        }

        @GetMapping("/actuator/health")
        Map<String, Object> health() {
            return Map.of("status", "UP");
        }

        private static String usernameOf(HttpServletRequest request) {
            return JwtAuthenticationFilter.callerOn(request)
                    .map(AuthenticatedCaller::username)
                    .orElse("nobody");
        }
    }
}

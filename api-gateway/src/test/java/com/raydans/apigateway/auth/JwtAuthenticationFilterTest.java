package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.apigateway.web.ErrorResponseWriter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
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
 * <p>The filter is wired in by hand so a failure here is the filter's rather
 * than the wiring's; {@code GatewayProxyBootTests} covers the wiring.
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
     * The same gateway with a clock as a parameter: expiry is a property of the
     * service verifying, not of the token, so the gateway has to be holding a
     * clock that has moved past it.
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
        // A string that is not a token at all, which is what a scanner sends.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aNonBearerAuthorizationHeaderIsRefused() throws Exception {
        // A valid token in the wrong scheme is not a valid token: accepting it
        // would make "did you send it correctly" indistinguishable from "were
        // you allowed to".
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, tokens.issue(44L, Set.of(Role.ADMIN)).token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() throws Exception {
        JwtService someoneElse = new JwtService(
                new JwtProperties("a-different-secret-that-is-also-long-enough", TTL), Clock.fixed(NOW, ZoneOffset.UTC));

        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(someoneElse.issue(44L, Set.of(Role.ADMIN)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        String token = tokens.issue(44L, Set.of(Role.ADMIN)).token();
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
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER))))
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
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("ORGANIZER")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("ADMIN")));
    }

    @Test
    void anOrganizerMayCreateAnEvent() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(43L, Set.of(Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminMayCreateAnEvent() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(44L, Set.of(Role.ADMIN))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void holdingOneRoleIsNotHoldingTheOthers() throws Exception {
        // The failure mode a containsAny check would let through: an organizer
        // token is not an admin token.
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(43L, Set.of(Role.ORGANIZER)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerMayNotReachTheManagementEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminMayReachTheManagementEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(44L, Set.of(Role.ADMIN)))))
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
    void onlyACustomerMayHoldSeats() throws Exception {
        // A Reservation belongs to a Customer (CONTEXT.md), and an organizer's
        // token is not one — it is authenticated, which is why this is 403 and
        // not 401. Asserted here as well as in RoleAuthorizerTest because the
        // filter is what actually reads the table.
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(43L, Set.of(Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuthorizedRequestReachesTheHandlerWithTheCallerOnIt() throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(42));
    }

    @Test
    void aCallerHoldingSeveralRolesIsNotRefused() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                bearerFor(tokens.issue(45L, Set.of(Role.CUSTOMER, Role.ORGANIZER))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // --- paths the filter must stay out of -----------------------------------

    @Test
    void loginIsReachableWithoutAToken() throws Exception {
        // POST, because that is the endpoint the rule names: the login surface
        // is public per method, not per prefix. 404 rather than 401 is the
        // point — it proves the request got past the filter and on to routing,
        // where this standalone setup has no login handler.
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anArbitraryPathUnderAuthIsNotReachableWithoutAToken() throws Exception {
        // The regression for a public "/auth/**": an endpoint that does not
        // exist there yet would have been public by default, and the next one
        // written there — a token refresh, a password reset — would inherit that
        // with nobody having decided it. 401 first, and only then the 404.
        mvc.perform(get("/auth/foo")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/foo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anArbitraryPathUnderAuthIsStillReachableWithAToken() throws Exception {
        // And the other half: narrowing the public rule must not make /auth
        // unreachable, only authenticated. 404 rather than 401, because there is
        // no such endpoint — the point is that the token was accepted.
        mvc.perform(get("/auth/foo").header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCorsPreflightIsNotRefused() {
        // Asked of the filter directly: a preflight is handled by the
        // DispatcherServlet's own CORS machinery, which has no handler adapter in
        // a standalone setup. What matters is whether the filter short-circuited.
        assertThat(runPreflight("/api/v1/reservations").reachedTheChain()).isTrue();
    }

    @Test
    void aPreflightToTheLoginSurfaceIsNotRefusedEither() {
        // The browser's first call is a cross-origin login POST, so the exemption
        // has to cover the auth surface or no browser can sign in at all.
        assertThat(runPreflight("/auth/login").reachedTheChain()).isTrue();
    }

    @Test
    void aCorsPreflightToTheManagementEndpointsIsRefused() {
        // CORS preflight is unauthenticated only for browser-facing routes;
        // management endpoints remain authenticated for every method. A preflight
        // cannot carry a token, so the honest answer is the 401 an
        // unauthenticated GET gets — not a pass.
        PreflightOutcome outcome = runPreflight("/actuator/health");

        assertThat(outcome.reachedTheChain()).isFalse();
        assertThat(outcome.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void aManagementPreflightIsRefusedForWantOfATokenAndNotOtherwise() {
        // The pair that makes the refusal above about authorization rather than
        // about preflights being refused in general: the same preflight, carrying
        // a usable ADMIN token, is authorized and let past the filter. Only
        // reachability is asserted — what the layers below make of the request
        // is not this filter's to promise.
        String admin = bearerFor(tokens.issue(44L, Set.of(Role.ADMIN)));
        String organizer = bearerFor(tokens.issue(43L, Set.of(Role.ORGANIZER)));

        assertThat(runPreflight("/actuator/health", admin).reachedTheChain()).isTrue();
        // And a token without the role still does not get through, which is the
        // rule actually being enforced.
        assertThat(runPreflight("/actuator/health", organizer).reachedTheChain()).isFalse();
    }

    @Test
    void aBareOptionsIsNotTreatedAsAPreflight() throws Exception {
        // A preflight is OPTIONS plus the two headers that make it one. Keying
        // the exemption on the method alone would let any client ask the
        // management endpoints a question with OPTIONS and get an answer.
        mvc.perform(options("/actuator/health")).andExpect(status().isUnauthorized());
    }

    /**
     * What the filter did with a preflight, as two facts. Whether it reached the
     * rest of the chain and what status came back are different claims, and
     * conflating them hides which layer broke: a request that reaches the chain
     * can still be refused downstream, and one the filter answered itself never
     * reaches it.
     */
    private record PreflightOutcome(boolean reachedTheChain, int status) {}

    private PreflightOutcome runPreflight(String path) {
        return runPreflight(path, null);
    }

    /**
     * @param bearer a token to send with the preflight, or null to send none — a
     *     real preflight never carries one, and asking what happens when it does
     *     is how the exemption is told apart from a blanket refusal
     */
    private PreflightOutcome runPreflight(String path, String bearer) {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                tokens, new RoleAuthorizer(), new ErrorResponseWriter(objectMapper));

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        if (bearer != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedTheChain = new AtomicBoolean();
        FilterChain chain = (req, res) -> reachedTheChain.set(true);

        try {
            filter.doFilter(request, response, chain);
        } catch (IOException | ServletException ex) {
            throw new AssertionError("The filter should pass a preflight straight through", ex);
        }

        return new PreflightOutcome(reachedTheChain.get(), response.getStatus());
    }

    // --- the header the gateway must not take from a client -------------------

    @Test
    void aTokenWhoseSubjectIsNotACustomerIdIsRefused() throws Exception {
        // Signed with the real key, so the signature check has nothing to say.
        // What refuses it is the subject (ADR 002): a token that does not say
        // which Customer it is for must not be turned into one by guessing.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerWithSubject("customer")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerWithSubject("99999999999999999999999")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aClientSuppliedRoleHeaderDoesNotAuthenticateAnyone() throws Exception {
        // The trust boundary in one test: downstream services read X-User-Roles
        // and have no authentication of their own (ADR 002), so if a client could
        // arrive already holding one the gateway would be decorative. This
        // asserts the filter half — no token, no access, whatever is claimed.
        mvc.perform(get("/api/v1/events").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aClientSuppliedRoleHeaderDoesNotLiftARoleRefusal() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(tokens.issue(42L, Set.of(Role.CUSTOMER))))
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private static String bearerFor(IssuedToken token) {
        return "Bearer " + token.token();
    }

    /**
     * A token signed with this gateway's real key and carrying a subject the
     * gateway would never mint. {@link JwtService#issue} only speaks in
     * Customer ids, so the shape under test is unreachable through it.
     */
    private String bearerWithSubject(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant issuedAt = Instant.parse("2026-09-27T10:00:00Z");
        return "Bearer " + Jwts.builder()
                .subject(subject)
                .claim(JwtService.ROLES_CLAIM, Role.namesOf(Set.of(Role.CUSTOMER)))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(TTL)))
                .signWith(key)
                .compact();
    }

    /**
     * Stands in for the services behind the gateway, so a permitted request has
     * somewhere to land: without a handler a 200 would prove nothing, because
     * MockMvc answers an unrouted request with a 404 indistinguishable from a
     * refusal. Each handler echoes the caller the filter authenticated, so a
     * test can check the request arrived as the right person.
     */
    @RestController
    static class StandInForTheProxiedServices {

        @GetMapping("/api/v1/events")
        Map<String, Object> listEvents(HttpServletRequest request) {
            return Map.of("customerId", customerIdOf(request), "ok", true);
        }

        @PostMapping("/api/v1/events")
        Map<String, Object> createEvent(HttpServletRequest request) {
            return Map.of("customerId", customerIdOf(request), "ok", true);
        }

        @PostMapping("/api/v1/reservations")
        Map<String, Object> createReservation(HttpServletRequest request) {
            return Map.of("customerId", customerIdOf(request), "ok", true);
        }

        @GetMapping("/actuator/health")
        Map<String, Object> health() {
            return Map.of("status", "UP");
        }

        /** The identity the gateway authenticated, which is the token subject (ADR 002). */
        private static long customerIdOf(HttpServletRequest request) {
            return JwtAuthenticationFilter.callerOn(request)
                    .map(AuthenticatedCaller::callerId)
                    .orElse(-1L);
        }
    }
}

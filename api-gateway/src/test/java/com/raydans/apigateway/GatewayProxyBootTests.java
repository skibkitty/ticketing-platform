package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gateway as it is actually deployed: the real application context, the
 * real route table from {@code application.yml}, and a real HTTP server standing
 * in for a service.
 *
 * <p>The other gateway tests are deliberately isolated — a hand-built filter, a
 * hand-built chain — because that is the right way to find out <em>which</em>
 * piece is wrong. This one exists to find out whether the pieces are wired
 * together at all, and there is a class of bug only a full context can show: a
 * route whose predicate does not match, a header filter the proxy never
 * consults, a filter registered at an order that puts it after the thing it was
 * meant to guard. Each of those would pass every other test in this module.
 *
 * <p>The downstream is the JDK's own {@code HttpServer} rather than a mock,
 * because the claim being tested is about bytes on a socket: that a header the
 * gateway decided is a header that arrives. A mock configured to agree with the
 * gateway's intentions would agree with them precisely when they were wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayProxyBootTests {

    @Autowired
    MockMvc mvc;

    /** The real bean, so the tokens here are the ones a caller would actually get. */
    @Autowired
    JwtService tokens;

    private static HttpServer reservationService;
    private static String reservationServiceUrl;

    /** Every request the stand-in service received, in order. */
    private static final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startTheStandInService() throws IOException {
        reservationService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reservationService.createContext("/api/v1/events", GatewayProxyBootTests::recordAndReply);
        reservationService.createContext("/api/v1/reservations", GatewayProxyBootTests::recordAndReply);
        reservationService.start();
        reservationServiceUrl = "http://127.0.0.1:" + reservationService.getAddress().getPort();
    }

    @AfterAll
    static void stopTheStandInService() {
        if (reservationService != null) {
            reservationService.stop(0);
        }
    }

    /**
     * Points the real route at the stand-in. Done through the real environment
     * variable so the route table under test is the one in {@code application.yml}
     * with its URI redirected — not a route rebuilt here, which would test a
     * different route from the one that ships.
     *
     * <p>Set as {@code RESERVATION_SERVICE_URL} rather than as
     * {@code spring.cloud.gateway.mvc.routes[0].uri} on purpose. Overriding one
     * indexed child of a list in a YAML file replaces that whole element: the
     * route arrives at the context with its id and predicates stripped out and no
     * predicate to match, which fails at startup for reasons that have nothing to
     * do with the gateway. Going through the variable the YAML already reads keeps
     * the test honest about the same seam a deployment uses.
     */
    @DynamicPropertySource
    static void redirectTheReservationRoute(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", () -> reservationServiceUrl);
    }

    @BeforeEach
    void forgetWhatTheServiceSaw() {
        received.clear();
    }

    @Test
    void anAuthorizedRequestIsProxiedToTheService() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1));

        assertThat(received).hasSize(1);
    }

    @Test
    void thePathAndMethodArriveUnchanged() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER)))
                .andExpect(status().isOk());

        // The services already serve the paths the gateway is asked for, so the
        // proxy must not rewrite them. A rewrite configured "helpfully" here
        // would 404 every route in the platform.
        assertThat(received.get(0).method()).isEqualTo("GET");
        assertThat(received.get(0).path()).isEqualTo("/api/v1/events");
    }

    @Test
    void aRequestBodyIsForwardedIntact() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 7, \"seatIds\": [1, 2]}"))
                .andExpect(status().isOk());

        // A gateway that dropped or re-encoded the body would let a caller
        // reserve the wrong seats, and the service would have no way to tell.
        assertThat(received.get(0).body()).isEqualTo("{\"eventId\": 7, \"seatIds\": [1, 2]}");
    }

    @Test
    void theCallersRolesArriveDownstream() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.ORGANIZER)))
                .andExpect(status().isOk());

        // The acceptance criterion, end to end: a service with no authentication
        // of its own (ADR 002) can still tell who is asking.
        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("ORGANIZER");
    }

    @Test
    void aForgedRoleHeaderFromTheClientDoesNotReachTheService() throws Exception {
        // The bypass, end to end. While the header filter left the inbound value
        // in place this arrived as "CUSTOMER,ADMIN" — and a downstream reading
        // only the first value would have believed the forgery.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER))
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("CUSTOMER");
    }

    @Test
    void aCorrelationIdIsGeneratedWhenTheCallerSuppliesNone() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER)))
                .andExpect(status().isOk());

        // Generated, not absent: user story 24 is that every request is
        // traceable, and a request with no id is a request that cannot be
        // followed through the services it touched.
        assertThat(received.get(0).header("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void aCorrelationIdTheCallerSuppliesIsForwardedRatherThanReplaced() throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER))
                        .header("X-Correlation-Id", "trace-me-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "trace-me-123"));

        assertThat(received.get(0).header("X-Correlation-Id")).isEqualTo("trace-me-123");
    }

    @Test
    void aRefusedRequestNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Not merely answered differently: a service that never sees the request
        // cannot be holding state for a caller who was not allowed to make it.
        assertThat(received).isEmpty();
    }

    @Test
    void anUnauthenticatedRequestNeverReachesTheService() throws Exception {
        mvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    @Test
    void theLoginRouteIsNotProxiedAndDoesNotNeedAToken() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"customer\", \"password\": \"customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        assertThat(received).isEmpty();
    }

    @Test
    void aTokenFromTheLoginRouteIsAcceptedOnTheNextRequest() throws Exception {
        // The two halves of the story in sequence, because they are useless
        // apart: a token that cannot be obtained, and an obtained token that
        // cannot be used, are the same failure seen from a caller's side.
        String loginBody = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"organizer\", \"password\": \"organizer\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = new ObjectMapper().readTree(loginBody).get("token").asText();

        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("ORGANIZER");
    }

    @Test
    void theReservationRouteIsProxiedToo() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(received).hasSize(1);
        assertThat(received.get(0).path()).isEqualTo("/api/v1/reservations");
    }

    @Test
    void aPathBelongingToAnotherServiceIsNotSentToThisOne() throws Exception {
        // The payments route points somewhere else entirely, and nothing is
        // listening for it here — so what matters is only that it did not arrive
        // at the stand-in. Asserting a status would be asserting on what an
        // unreachable host happens to do.
        mvc.perform(get("/api/v1/payments/1").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.CUSTOMER)));

        assertThat(received).isEmpty();
    }

    // --- the paths that are the gateway's own, not proxied ---------------------

    @Test
    void aManagementEndpointIsNotAnsweredWithoutAnAdminToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagementEndpointIsAnsweredForAnAdmin() throws Exception {
        // The reason the authentication is a servlet filter rather than a gateway
        // route filter: /actuator is a container route the proxy never sees, so a
        // rule expressed as a route would leave the platform's own management
        // surface the one thing in the process nobody checks. Only a full context
        // can show that the filter really does run ahead of it.
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.ADMIN)))
                .andExpect(status().isOk());

        // And not to an organizer, who has every reason to want a service's
        // internals and no business reading them.
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearerFor(Role.ORGANIZER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPreflightFromAnAllowedOriginIsAnsweredWithoutAToken() throws Exception {
        // A browser sends this before it has a token, and will never send a token
        // with it. Answering 401 would break every browser client in a way that
        // looks like a CORS misconfiguration, and OPTIONS performs no action, so
        // there is nothing here to authorize.
        mvc.perform(options("/api/v1/events").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }

    // --- the stand-in service -------------------------------------------------

    private static void recordAndReply(HttpExchange exchange) throws IOException {
        record(exchange);
        byte[] body = "{\"eventId\": 1, \"name\": \"probed by the gateway\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        // Case-insensitive, because HTTP header names are, and a lookup that
        // only worked for the exact casing the JDK happens to report would
        // make a passing test a matter of luck.
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        received.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                headers,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** What the stand-in service saw, so a test can assert on what actually arrived. */
    private record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            // Joined rather than first-only: a duplicate header would be a bug
            // worth seeing whole, and taking the first value would hide it.
            return String.join("|", values);
        }
    }

    private String bearerFor(Role role) {
        return "Bearer " + tokens.issue(role.name().toLowerCase(Locale.ROOT), Set.of(role)).token();
    }
}

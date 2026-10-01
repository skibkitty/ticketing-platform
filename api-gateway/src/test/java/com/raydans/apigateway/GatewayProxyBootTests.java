package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import javax.crypto.SecretKey;
import org.hamcrest.Matchers;
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
 * <p>The other gateway tests are isolated because that is the right way to find
 * out <em>which</em> piece is wrong. This one asks whether the pieces are wired
 * together, which is a class of bug only a full context shows: a route whose
 * predicate does not match, a header filter the proxy never consults, a filter
 * ordered after the thing it guards. Each would pass every other test here.
 *
 * <p>The downstream is the JDK's own {@code HttpServer} rather than a mock
 * because the claim is about bytes on a socket: a mock configured to agree with
 * the gateway's intentions agrees precisely when they were wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayProxyBootTests {

    /** The Customer ids the demo callers in {@code application.yml} belong to. */
    private static final long CUSTOMER_ID = 42L;
    private static final long ORGANIZER_ID = 43L;
    private static final long ADMIN_ID = 44L;

    private static final String CUSTOMER_PASSWORD = "the-customer-password";
    private static final String ORGANIZER_PASSWORD = "the-organizer-password";
    private static final String ADMIN_PASSWORD = "the-admin-password";

    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";
    private static final Duration TTL = Duration.ofHours(1);

    /** Stated by this test rather than inherited from {@code application.yml}'s default. */
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    /** The two correlation-id forms the platform propagates rather than replaces. */
    private static final String A_CALLERS_CORRELATION_ID = "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b";

    private static final String A_CALLERS_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

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
        // The notification routes, including the operator's, so a request that the
        // authorization table admits can be observed reaching an upstream rather
        // than refused by an unreachable host (ADR 011).
        reservationService.createContext("/api/v1/notifications", GatewayProxyBootTests::recordAndReply);
        reservationService.createContext("/api/v1/admin", GatewayProxyBootTests::recordAndReply);
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
     * Points the real route at the stand-in, through the same environment
     * variable a deployment would set. Overriding
     * {@code spring.cloud.gateway.mvc.routes[0].uri} instead would replace that
     * whole list element, so the route would reach the context with its id and
     * predicates stripped and no predicate to match.
     */
    @DynamicPropertySource
    static void redirectTheReservationRoute(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", () -> reservationServiceUrl);
        // The same stand-in, so the operator's notification route has an upstream to
        // reach. These tests assert what the gateway forwards and to whom, not which
        // service implements it.
        registry.add("NOTIFICATION_SERVICE_URL", () -> reservationServiceUrl);
        // Nothing secret ships with the gateway, so a test has to be a
        // deployment and say what the key is — as does every password in the
        // caller directory, which is how a real one is configured.
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> CUSTOMER_PASSWORD);
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> ORGANIZER_PASSWORD);
        registry.add("DEMO_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("CORS_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN);
    }

    @BeforeEach
    void forgetWhatTheServiceSaw() {
        received.clear();
    }

    @Test
    void anAuthorizedRequestIsProxiedToTheService() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1));

        assertThat(received).hasSize(1);
    }

    @Test
    void thePathAndMethodArriveUnchanged() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        // The services already serve the paths the gateway is asked for, so a
        // "helpful" rewrite would 404 every route in the platform.
        assertThat(received.get(0).method()).isEqualTo("GET");
        assertThat(received.get(0).path()).isEqualTo("/api/v1/events");
    }

    @Test
    void aRequestBodyIsForwardedIntact() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 7, \"seatIds\": [1, 2]}"))
                .andExpect(status().isOk());

        // A gateway that dropped or re-encoded the body would let a caller
        // reserve the wrong seats, and the service would have no way to tell.
        assertThat(received.get(0).body()).isEqualTo("{\"eventId\": 7, \"seatIds\": [1, 2]}");
    }

    @Test
    void theCallersRolesArriveDownstream() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER)))
                .andExpect(status().isOk());

        // A service with no authentication of its own (ADR 002) can still tell
        // who is asking.
        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("ORGANIZER");
    }

    @Test
    void aForgedRoleHeaderFromTheClientDoesNotReachTheService() throws Exception {
        // The bypass, end to end. With the inbound value left in place this
        // arrived as "CUSTOMER,ADMIN", and a downstream reading only the first
        // value would have believed the forgery.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("CUSTOMER");
    }

    @Test
    void aRoleHeaderSentTwiceByTheClientReachesTheServiceOnce() throws Exception {
        // The same bypass in the shape two curl -H flags produce, which is a
        // single header with two values rather than one comma-joined value. The
        // gateway's own request reader would collapse these to "ADMIN, CUSTOMER"
        // before the filter saw them, so the value that matters is the one the
        // service is handed.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-User-Roles", "ADMIN", "ORGANIZER"))
                .andExpect(status().isOk());

        // header() joins repeated values with "|", so a second one surviving is a
        // failure here rather than a value a reader has to notice is wrong.
        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("CUSTOMER");
    }

    // --- X-Customer-Id: the identity a reservation is booked against -----------

    @Test
    void theCustomersIdFromTheTokenIsForwardedDownstream() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        // The token's subject, and nothing else (ADR 002): the service books
        // the reservation against 42 because that is the Customer the gateway
        // verified the signature for.
        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void theCustomerIdIsForwardedOnAReservationTheCallerHasToOwn() throws Exception {
        // The route where the id actually decides something. Booking seats
        // against the wrong Customer is the whole harm, so it is asserted there
        // rather than only on a browse.
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void anOrganizerIsForwardedNoCustomerIdBecauseTheyAreNotOne() throws Exception {
        // The distinction the whole identity model turns on, end to end. The
        // organizer authenticates and is forwarded, but there is no Customer id
        // to give them, so the header is absent rather than carrying the
        // organizer's own id dressed up as a Customer's.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER)))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isNull();
        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("ORGANIZER");
    }

    @Test
    void anOrganizersForgedCustomerIdIsStrippedEvenThoughTheyGetNoReplacement() throws Exception {
        // The case that makes the strip unconditional. This caller is given no
        // header, so a strip deferred to the code that sets one would leave the
        // client's value standing — the organizer's token would then carry
        // customer 42 into a service that trusts it.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER))
                        .header("X-Customer-Id", "42"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isNull();
    }

    @Test
    void anOrganizerCannotReserveASeat() throws Exception {
        // A Reservation belongs to a Customer, so there is nothing for the
        // gateway to book this against. Refused here rather than at the service,
        // which is one hop further and has no authentication of its own to
        // refuse it with.
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anAdminCannotReserveASeatEither() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    // --- a Customer's reservations are read by identity (ADR 014) ---------------
    //
    // The gateway's half of that invariant, and it is the half that cannot be
    // tested anywhere else: that the id on a read is the one the token speaks for.
    // The service's half — that a reservation is only returned to its owner — is
    // asserted in ReservationFlowBootTests against a real database. Between them
    // they cover the path from a JWT to Customer A being unable to read Customer
    // B's data, and neither half is sufficient alone: a gateway that published no
    // identity would leave the service nothing to scope by, and a service that
    // trusted a parameter would not care what the gateway published.

    @Test
    void aReservationReadIsForwardedWithTheCustomersVerifiedIdAndNothingElse() throws Exception {
        mvc.perform(get("/api/v1/reservations/7")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        // The same treatment the write gets. A read is scoped by this id, so
        // leaving it off a GET would be the bypass; publishing the caller's own
        // choice of id on a GET would be the same bypass again.
        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
        assertThat(received.get(0).method()).isEqualTo("GET");
        assertThat(received.get(0).path()).isEqualTo("/api/v1/reservations/7");
    }

    @Test
    void aCustomerIdForgedOnAReservationReadIsReplacedByTheOnesInTheToken() throws Exception {
        // The bypass this closes, at the boundary it would have to cross. The
        // inbound header is removed unconditionally and replaced from the
        // verified subject, so the service is never handed 999 to act on.
        mvc.perform(get("/api/v1/reservations/7")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Customer-Id", "999"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void aCustomerIdForgedTwiceOnAReservationReadDoesNotReachTheServiceTwice() throws Exception {
        // The two-curl shape, which is one header with two values rather than one
        // comma-joined value — a downstream reading only the first would believe
        // the forgery.
        mvc.perform(get("/api/v1/reservations/7")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Customer-Id", "999", "1"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void aCustomerIdQueryParameterOnAReservationListCannotDisplaceTheVerifiedHeader() throws Exception {
        // The list half. The parameter is not the gateway's to remove — it reaches
        // the service, which refuses it when it disagrees (ADR 014) — but what
        // matters here is that it travels alongside the header and not instead of
        // it: a Customer's own id is on the request whatever they asked for.
        mvc.perform(get("/api/v1/reservations")
                        .param("customerId", "999")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
        assertThat(received.get(0).path()).isEqualTo("/api/v1/reservations");
    }

    @Test
    void aCustomerIdForgedOnAReservationListAlongsideAQueryParameterStillBecomesTheTokens() throws Exception {
        // Both bypasses attempted at once, which is what a probing client would
        // send. Neither survives: the header is 42, and 999 is on the query for
        // the service to refuse.
        mvc.perform(get("/api/v1/reservations")
                        .param("customerId", "999")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Customer-Id", "999"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void anOrganizerCanReadNobodyCustomersReservations() throws Exception {
        // An organizer holds no CUSTOMER role, so there is no id to publish and
        // the reservation surface refuses it here rather than forwarding a request
        // that would arrive with no identity to scope by.
        mvc.perform(get("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER))
                        .header("X-Customer-Id", "42"))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anAdminCanReadNobodyCustomersReservationsEither() throws Exception {
        mvc.perform(get("/api/v1/reservations/7")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN))
                        .header("X-Customer-Id", "42"))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anUnauthenticatedReservationReadForwardsNothing() throws Exception {
        // No token, no read: the header must not be the one thing a reservation
        // request gets through without one.
        mvc.perform(get("/api/v1/reservations/7").header("X-Customer-Id", "42"))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    // --- whose notifications, and who may read whose (ADR 011) -------------------

    @Test
    void aCustomersInboxRequestIsForwardedWithTheirVerifiedId() throws Exception {
        // The gateway's half of identity-bound reads: it puts the caller's own id on
        // the request, so the service never has to be told whose inbox to read and
        // never has to believe a parameter (ADR 002, ADR 011).
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void anOrganizerIsNotForwardedToAnyCustomersInbox() throws Exception {
        // An organizer holds no CUSTOMER role, so it is refused here rather than
        // forwarded to arrive with no id. The two agree — a caller with no id could
        // not be given an inbox — and the gateway is the one that says so first.
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER)))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void aCustomerCannotReadAnInboxThroughTheOperatorRoute() throws Exception {
        // The whole reason the operator's read is a separate path rather than a flag
        // on this one: nothing about the self-service route changes to widen it.
        mvc.perform(get("/api/v1/admin/customers/" + CUSTOMER_ID + "/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anOrganizerCannotReadAnInboxThroughTheOperatorRouteEither() throws Exception {
        mvc.perform(get("/api/v1/admin/customers/" + CUSTOMER_ID + "/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER)))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anAdminMayReadAnyCustomersInboxThroughTheOperatorRoute() throws Exception {
        // The deliberate capability from ADR 011, and the only role that has it.
        mvc.perform(get("/api/v1/admin/customers/" + CUSTOMER_ID + "/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk());

        assertThat(received.get(0).path())
                .isEqualTo("/api/v1/admin/customers/42/notifications");
        // The admin is not a Customer, so no X-Customer-Id rides along: the route's
        // scope is the path, and a header implying otherwise would be a second,
        // contradictory statement about who the request is for.
        assertThat(received.get(0).header("X-Customer-Id")).isNull();
    }

    @Test
    void anAdminReadingAnotherCustomersInboxCannotForgeTheHeader() throws Exception {
        mvc.perform(get("/api/v1/admin/customers/" + CUSTOMER_ID + "/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN))
                        .header("X-Customer-Id", "999"))
                .andExpect(status().isOk());

        // Stripped even though this caller is never given one. The strip is not inside
        // the branch that sets the header, so a value the client sent cannot survive
        // on any authenticated request.
        assertThat(received.get(0).header("X-Customer-Id")).isNull();
    }

    @Test
    void theAdminPrefixIsGatedWholesaleRatherThanPerRoute() throws Exception {
        // ADMIN's reach is decided by the prefix, so a path under it that no service
        // serves is still refused to a Customer — which is the property that makes it
        // safe to add an operator route later without re-deciding the boundary.
        mvc.perform(get("/api/v1/admin/anything-at-all")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        assertThat(received).isEmpty();
    }

    @Test
    void anAdminPathNoServiceServesIsNotProxiedToTheNotificationService() throws Exception {
        // The regression for a route that claims the whole /api/v1/admin/** prefix.
        // This caller is authorized for everything under it, so the request is not
        // refused — it simply has no route, and the answer is the gateway's own 404.
        // Under the prefix-wide route it would have been forwarded to
        // notification-service instead, so a future /api/v1/admin/events would have
        // been proxied to whichever service's route happened to be listed first
        // rather than to the one that owns the data.
        //
        // Both halves are asserted, because they fail differently: the status is
        // what the caller sees, and the empty `received` is what actually proves the
        // request was not handed to a service with no authentication of its own
        // (ADR 011). A 404 asserted alone would also be satisfied by a route that
        // proxied to a service which then 404'd, having already leaked the path.
        mvc.perform(get("/api/v1/admin/something-else")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(received)
                .as("an unrouted admin path must not reach notification-service on its way to a 404")
                .isEmpty();
    }

    @Test
    void anAdminPathIsNotProxiedToTheNotificationServiceWhateverItsShape() throws Exception {
        // The other half of the narrowing, and the reason it is worth a test of its
        // own: a path that is under the prefix but off the shape is not this
        // service's route either, so it 404s at the gateway too. Under
        // /api/v1/admin/** these would have been forwarded and left to the service's
        // own 404 — after the request had already arrived, and with the caller's
        // identity headers on it.
        for (String path : List.of(
                "/api/v1/admin",
                "/api/v1/admin/",
                "/api/v1/admin/customers",
                "/api/v1/admin/customers/42",
                "/api/v1/admin/customers/42/notifications/extra",
                "/api/v1/admin/customers/42/events")) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                    .andExpect(status().isNotFound());

            // The path is named on the assertion rather than the status: the status
            // is 404 for all six, so a failure has to say which one was forwarded.
            assertThat(received).as("GET %s", path).isEmpty();
        }
    }

    @Test
    void theNotificationRouteStillServesBothItsOwnAndItsOperatorPath() throws Exception {
        // The narrowing is only safe if it keeps working what it was for, so the
        // two shapes the service actually implements are asserted in one place
        // rather than relying on the other inbox tests to have noticed.
        mvc.perform(get("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/customers/" + CUSTOMER_ID + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk());

        assertThat(received).extracting(RecordedRequest::path)
                .containsExactly("/api/v1/notifications", "/api/v1/admin/customers/42/notifications");
    }

    @Test
    void aCustomerIdForgedByTheClientIsReplacedByTheOnesInTheToken() throws Exception {
        // The bypass, end to end. A valid token for customer 42 plus a header
        // naming 999 is a request to book someone else's seats, and the service
        // that would act on it has no authentication of its own to notice.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Customer-Id", "999"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void aForgedCustomerIdIsNotAppendedToTheRealOne() throws Exception {
        // "42,999" or "999,42" — a downstream reading only the first value would
        // believe whichever half it read.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Customer-Id", "999", "1"))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
    }

    @Test
    void anUnauthenticatedRequestPropagatesNoCustomerIdentity() throws Exception {
        // No token, no identity: the header must not be the one thing a request
        // gets through without one.
        mvc.perform(get("/api/v1/events").header("X-Customer-Id", "999"))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    @Test
    void aTokenWhoseSubjectIsNotACustomerIdIsRefusedAndPropagatesNothing() throws Exception {
        // Signed with the real key, so the signature check has nothing to say
        // about it: what refuses it is a subject that is not a Customer id
        // (ADR 002). Guessing an id out of the string is what must not happen.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerWithSubject("customer"))
                        .header("X-Customer-Id", "999"))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    @Test
    void aTokenFromTheLoginRouteSpeaksForTheCustomersIdAndNotTheUsername() throws Exception {
        // The two halves in sequence, because they are useless apart: the token
        // the login surface mints has to carry the id, or nothing downstream can
        // know who the caller is. The caller types "customer" and the request
        // goes out as 42.
        String token = logInAs("customer", CUSTOMER_PASSWORD);

        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-Customer-Id")).isEqualTo("42");
        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("CUSTOMER");
    }

    @Test
    void aCorrelationIdIsGeneratedWhenTheCallerSuppliesNone() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        // Generated, not absent: a request with no id cannot be followed through
        // the services it touched.
        assertThat(received.get(0).header("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void aCorrelationIdTheCallerSuppliesIsForwardedRatherThanReplaced() throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Correlation-Id", A_CALLERS_CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", A_CALLERS_CORRELATION_ID));

        assertThat(received.get(0).header("X-Correlation-Id")).isEqualTo(A_CALLERS_CORRELATION_ID);
    }

    @Test
    void aTraceIdTheCallerSuppliesIsForwardedToo() throws Exception {
        // The other id shape the platform propagates: a 32-character hexadecimal
        // trace id, which is what a caller holding a W3C traceparent or an
        // OpenTelemetry span already has. Refusing it would mean an integration
        // that worked had to change, for no security gain over accepting it.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Correlation-Id", A_CALLERS_TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", A_CALLERS_TRACE_ID));

        assertThat(received.get(0).header("X-Correlation-Id")).isEqualTo(A_CALLERS_TRACE_ID);
    }

    @Test
    void aCorrelationIdThePlatformWillNotPropagateIsReplacedEndToEnd() throws Exception {
        // The bypass this closes, end to end. The caller's value reaches the MDC
        // of every service the request touches and is written on the response, so
        // an unvalidated one is a caller-supplied field in the platform's logs and
        // in its own response — a newline in it is a forged log line.
        //
        // The gateway's header filter copies the MDC, so the value the service saw
        // and the value the caller was told are the same generated UUID. That is
        // the property that matters: a caller quoting one and a log line recording
        // the other would not be talking about the same request.
        for (String rejected : List.of(
                "trace-me-123",
                "abc\nINFO admin authenticated",
                "0".repeat(32),
                "x".repeat(5_000))) {
            received.clear();

            MvcResult result = mvc.perform(get("/api/v1/events")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                            .header("X-Correlation-Id", rejected))
                    .andExpect(status().isOk())
                    .andReturn();

            String downstream = received.get(0).header("X-Correlation-Id");
            String onTheResponse = result.getResponse().getHeader("X-Correlation-Id");
            String described = rejected.length() > 40 ? "an oversized value" : rejected;

            assertThat(downstream).as("%s must not reach a service", described).isNotEqualTo(rejected).isNotBlank();
            // Parses, and is in the canonical form, so this is an id the platform
            // minted rather than another near-miss that happened to survive.
            assertThat(UUID.fromString(downstream).toString())
                    .as("%s downstream", described)
                    .isEqualTo(downstream);
            assertThat(onTheResponse).as("%s on the response", described).isNotEqualTo(rejected).isNotBlank();
        }
    }

    @Test
    void aBlankCorrelationIdBecomesAGeneratedOne() throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header("X-Correlation-Id", "   "))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", Matchers.matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));

        String downstream = received.get(0).header("X-Correlation-Id");
        assertThat(UUID.fromString(downstream).toString()).isEqualTo(downstream);
    }

    @Test
    void aRefusedRequestNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
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
                        .content("{\"username\": \"customer\", \"password\": \"%s\"}".formatted(CUSTOMER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        assertThat(received).isEmpty();
    }

    @Test
    void anArbitraryPathUnderAuthIsNotPublicJustBecauseLoginIs() throws Exception {
        // The regression for a public "/auth/**": anything added under /auth
        // would have been reachable with no token at all, and the next routes
        // there are a refresh and a password reset.
        mvc.perform(get("/auth/foo")).andExpect(status().isUnauthorized());
        assertThat(received).isEmpty();
    }

    @Test
    void aTokenFromTheLoginRouteIsAcceptedOnTheNextRequest() throws Exception {
        // The two halves in sequence, because they are useless apart: a token
        // that cannot be obtained and one that cannot be used are the same
        // failure from a caller's side.
        String token = logInAs("organizer", ORGANIZER_PASSWORD);

        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(received.get(0).header("X-User-Roles")).isEqualTo("ORGANIZER");
    }

    @Test
    void theReservationRouteIsProxiedToo() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(received).hasSize(1);
        assertThat(received.get(0).path()).isEqualTo("/api/v1/reservations");
    }

    @Test
    void aPathBelongingToAnotherServiceIsNotSentToThisOne() throws Exception {
        // Nothing is listening for the payments route, so what matters is only
        // that it did not arrive here. Asserting a status would be asserting on
        // what an unreachable host happens to do.
        mvc.perform(get("/api/v1/payments/1").header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER)));

        assertThat(received).isEmpty();
    }

    // --- the paths that are the gateway's own, not proxied ---------------------

    @Test
    void aManagementEndpointIsNotAnsweredWithoutAnAdminToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagementEndpointIsAnsweredForAnAdmin() throws Exception {
        // /actuator is a container route the proxy never sees, so only a full
        // context can show that the filter runs ahead of it.
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk());

        // And not to an organizer, who has every reason to want a service's
        // internals and no business reading them.
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearerFor(ORGANIZER_ID, Role.ORGANIZER)))
                .andExpect(status().isForbidden());
    }

    // --- CORS: the browser's two-step, and where the boundary is ---------------

    @Test
    void aPreflightFromAnAllowedOriginIsAnsweredWithoutAToken() throws Exception {
        // A browser sends this before it has a token, and never sends one with
        // it; a 401 here would look to a client like a broken CORS setup.
        mvc.perform(options("/api/v1/events").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void aPreflightFromADisallowedOriginIsNotGrantedTheAllowedOrigin() throws Exception {
        // The other side of the same boundary. Answering a preflight is not
        // itself a disclosure, but reflecting an arbitrary origin back with
        // allow-credentials is a browser asking "may I read this customer's
        // data", and the answer has to be no.
        int status = mvc.perform(options("/api/v1/events").header(HttpHeaders.ORIGIN, "http://attacker.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andReturn()
                .getResponse()
                .getStatus();

        // Not "is it 403": that is Spring's choice of refusal. The property is
        // that a disallowed origin is not told it may read the response.
        assertThat(status).isNotIn(IntStream.rangeClosed(200, 299).boxed().toList());
        assertThat(received).isEmpty();
    }

    @Test
    void aPreflightDoesNotCarryAnyOfTheProtectedEndpointsData() throws Exception {
        // The exemption is for the browser's handshake, not for reading the
        // route. The response is the CORS headers and nothing else, and the
        // service behind the route is never asked.
        String body = mvc.perform(options("/api/v1/reservations").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).isEmpty();
        assertThat(received).isEmpty();
    }

    @Test
    void aPreflightToAManagementEndpointIsNotAnswered() throws Exception {
        // The pair that keeps the exemption honest on the other side. A preflight
        // to /api is answered because a browser asks it; a preflight to
        // /actuator goes through the ordinary ADMIN decision like any other
        // request, so the management endpoints are the operator's on every method
        // rather than on all of them but OPTIONS.
        //
        // 401 rather than 403 because a preflight cannot carry a token: the
        // browser never sends one with it. The point is that it is not answered.
        mvc.perform(options("/actuator/health").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // And the exemption still works where it is meant to, from the same
        // origin, in the same test: a browser is not put off the API by this.
        mvc.perform(options("/api/v1/events").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void aManagementPreflightIsNotAnsweredEvenForAnAdmin() throws Exception {
        // An ADMIN is authorized by the gateway's own rule — JwtAuthenticationFilterTest
        // asserts the filter lets this past — and the handshake is still not granted,
        // because the shared CORS mapping in `common` is registered against the
        // request-mapping handler while the actuator is served by a different one with
        // no CORS configuration.
        //
        // The status is deliberately not asserted. What this holds is that the
        // management surface never gets a successful CORS answer, and how a refused
        // handshake is spelled is Spring's business, not the contract.
        MvcResult result = mvc.perform(options("/actuator/health")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.OK.value());
    }

    @Test
    void aManagementPreflightCarriesNoManagementInformation() throws Exception {
        // Nothing about the gateway's internals is reachable through a preflight: the
        // body is the gateway's own error shape with no actuator payload in it, so
        // there is no oracle to read the management surface through.
        String body = mvc.perform(options("/actuator/health").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("\"UP\"").doesNotContain("components").doesNotContain("diskSpace");
        // The same endpoint over GET with an ADMIN token does report status, so
        // the absence above is a decision rather than a route that does not exist.
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void aPreflightIsNotAskedToAuthenticateAndAnOrdinaryRequestStillIs() throws Exception {
        // The pair that keeps the exemption honest: the same origin, the same
        // path, and the preflight is answered while the real request is not. A
        // CORS fix that had quietly widened the public rule would pass the first
        // and fail this.
        mvc.perform(options("/api/v1/events").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/events").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    @Test
    void anOrdinaryRequestFromADisallowedOriginIsNotGrantedThatOrigin() throws Exception {
        // CORS decides what a browser may read, not who the caller is, so it
        // never produces a grant for an origin the deployment did not list. What
        // it does with the request itself is Spring's business and not this
        // gateway's; the property asserted here is the missing header.
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(CUSTOMER_ID, Role.CUSTOMER))
                        .header(HttpHeaders.ORIGIN, "http://attacker.example"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void anOrdinaryRequestFromADisallowedOriginStillNeedsAToken() throws Exception {
        // The filter runs ahead of the CORS machinery, so a disallowed origin
        // is not a way to skip authentication — and an unauthenticated request
        // never reaches the service, from anywhere.
        mvc.perform(get("/api/v1/events").header(HttpHeaders.ORIGIN, "http://attacker.example"))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
    }

    @Test
    void aNormalRequestFromAnAllowedOriginStillNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());

        assertThat(received).isEmpty();
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
        // Case-insensitive, because HTTP header names are.
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
            // Joined rather than first-only, so a duplicate header is visible whole.
            return String.join("|", values);
        }
    }

    private String logInAs(String username, String password) throws Exception {
        return new ObjectMapper()
                .readTree(mvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("token")
                .asText();
    }

    private String bearerFor(long customerId, Role... roles) {
        return "Bearer " + tokens.issue(customerId, Set.of(roles)).token();
    }

    /**
     * A correctly signed token carrying a subject {@link JwtService#issue} would
     * never mint, so the subject check can be exercised over HTTP. Without this
     * seam the only way to reach the shape would be to weaken the code it
     * guards.
     */
    private static String bearerWithSubject(String subject) {
        // Dated from the real clock, unlike the tokens minted by the bean: a
        // fixed date would make this an expired token, and the request would be
        // refused for the wrong reason — which is the failure mode a test like
        // this is most prone to.
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(subject)
                .claim(JwtService.ROLES_CLAIM, Role.namesOf(Set.of(Role.CUSTOMER)))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key)
                .compact();
    }
}

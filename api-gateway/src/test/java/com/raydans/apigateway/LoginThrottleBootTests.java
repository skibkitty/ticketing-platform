package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.LoginThrottleProperties;
import com.raydans.apigateway.auth.Role;
import java.time.Duration;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The login limiter over HTTP: the real context, the real filter chain, the real
 * controller, and the real error body a caller reads (#43).
 *
 * <p>A separate class from {@code GatewayProxyBootTests} for one reason, which is
 * worth stating because it looks like tidiness and is not: the counters live in
 * the application context, and a Spring test context is cached and shared by
 * every class that asks for the same one. Failures spent by one test would be
 * failures another test sees — so a class that deliberately burns a login budget
 * would leave another class's legitimate logins refused, in an order no test
 * controls. This class therefore configures a caller of its own, which is both
 * the account these tests are allowed to lock out and a second, distinct reason
 * for its context to be its own.
 *
 * <p>The limits are not restated here. They are read off the bound
 * {@link LoginThrottleProperties}, so these tests say "the deployment answers
 * this many guesses and refuses the next" for whatever the deployment actually
 * ships — and a limiter block that failed to bind would fail the context rather
 * than quietly assert a number nothing uses.
 *
 * <p>Every test gives its requests a remote address of its own, and every test
 * that spends a login budget names a login no other test in this class spends.
 * Both are needed, and the second one is not obvious: the budget is two axes, a
 * remote address that is local to a test and an account that is not. A new test
 * that reached for a name already locked out by an earlier one would fail on
 * whichever ran second, in an order no test controls.
 */
@SpringBootTest(
        properties = {
            "app.gateway.callers.probe.caller-id=45",
            "app.gateway.callers.probe.password=the-probe-password",
            "app.gateway.callers.probe.roles[0]=CUSTOMER"
        })
@AutoConfigureMockMvc
class LoginThrottleBootTests {

    private static final Duration TTL = Duration.ofHours(1);
    private static final String SIGNING_KEY = "a-signing-key-this-test-only-ever-signs-with";

    private static final String CUSTOMER_PASSWORD = "the-customer-password";
    private static final String ORGANIZER_PASSWORD = "the-organizer-password";
    private static final String ADMIN_PASSWORD = "the-admin-password";

    /** The caller this class configures for itself, and the one it locks out. */
    private static final String PROBE_USERNAME = "probe";
    private static final String PROBE_PASSWORD = "the-probe-password";

    /** Used only by the refund test, so its counters are its own. */
    private static final String CUSTOMER_USERNAME = "customer";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    /** The limits this deployment actually bound, rather than the ones a test wrote down. */
    @Autowired
    LoginThrottleProperties limits;

    @DynamicPropertySource
    static void aDeploymentRatherThanAStub(DynamicPropertyRegistry registry) {
        // Nothing secret ships with the gateway, so a test has to be a deployment
        // and say what the key and each of the demo passwords is.
        registry.add("JWT_SECRET", () -> SIGNING_KEY);
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> CUSTOMER_PASSWORD);
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> ORGANIZER_PASSWORD);
        registry.add("DEMO_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Test
    void theConfiguredNumberOfWrongPasswordsIsAnsweredAndTheNextOneIsRefused() throws Exception {
        for (int attempt = 1; attempt <= limits.maxFailuresPerAccount(); attempt++) {
            mvc.perform(login(from("198.51.100.1"), "guessed-account-a", "not-the-password"))
                    .andExpect(status().isUnauthorized())
                    // A 401 must not tell a caller to wait: it has nothing to do
                    // with how fast they are going.
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }

        mvc.perform(login(from("198.51.100.1"), "guessed-account-a", "not-the-password"))
                .andExpect(status().isTooManyRequests())
                // The header is the point of a 429: without it a client cannot
                // tell a rate limit from a wrong password and will retry a
                // password it has no reason to change.
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.path").value("/auth/login"));
    }

    @Test
    void retryAfterIsTheRestOfTheWindowAndNotAGuess() throws Exception {
        lockOutAccount("198.51.100.2", "guessed-account-b");

        String retryAfter = mvc.perform(login(from("198.51.100.2"), "guessed-account-b", "not-the-password"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.RETRY_AFTER);

        // The whole window, give or take the second of real time that passed while
        // this test was making its requests, because this is the first refusal of
        // it. A caller that waited this long would be answered rather than refused
        // again, which is what the number has to mean: rounding down would send
        // them back one second early, for the whole window, every time.
        assertThat(Long.parseLong(retryAfter))
                .isBetween(limits.window().toSeconds() - 1, limits.window().toSeconds());
    }

    @Test
    void theRefusalSaysNothingAboutWhetherTheLoginNameExists() throws Exception {
        // #43's acceptance criterion, over the wire. Under the limit this is the
        // 401 that already existed; at the limit it is the part that is new,
        // because a limiter that tripped sooner for a real account would answer
        // the question the 401 is built to refuse to answer.
        String unknownUnderLimit = mvc.perform(login(from("198.51.100.3"), "guessed-account-c", "whatever"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String knownUnderLimit = mvc.perform(login(from("198.51.100.4"), "organizer", "whatever"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(comparableFieldsOf(unknownUnderLimit)).isEqualTo(comparableFieldsOf(knownUnderLimit));

        // And at the limit, for a name that exists and one that does not.
        lockOutAccount("198.51.100.5", "admin");
        lockOutAccount("198.51.100.6", "guessed-account-d");
        String knownAtLimit = mvc.perform(login(from("198.51.100.5"), "admin", "whatever"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownAtLimit = mvc.perform(login(from("198.51.100.6"), "guessed-account-d", "whatever"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(comparableFieldsOf(knownAtLimit)).isEqualTo(comparableFieldsOf(unknownAtLimit));
        assertThat(comparableFieldsOf(knownAtLimit))
                .as("and the limit must not say which of the two it was")
                .doesNotContain("admin")
                .doesNotContain("guessed-account-d");
    }

    @Test
    void aForwardedHeaderOfTheCallersChoosingDoesNotResetTheLimit() throws Exception {
        // The invariant that makes the per-address budget worth having, asserted
        // the way an attacker would try it, and varying the login name as well as
        // the header so that neither the account counter nor a naive address key
        // can be the thing doing the work. What is left is the socket the request
        // arrived on, and that is what has to accumulate: this platform has one hop
        // and trusts nothing, so X-Forwarded-For is the attacker's own and is
        // never read (ADR 015).
        for (int attempt = 1; attempt <= limits.maxFailuresPerAddress(); attempt++) {
            mvc.perform(login(from("198.51.100.7"), "forwarded-account-" + attempt, "not-the-password")
                            .header("X-Forwarded-For", "203.0.113." + attempt))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(login(from("198.51.100.7"), "forwarded-account-1", "not-the-password")
                        .header("X-Forwarded-For", "203.0.113.250"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void changingTheLoginNameOnEveryAttemptDoesNotOutlastTheAddressBudget() throws Exception {
        // The other way out, and the one per-account limiting alone cannot close:
        // every one of these names is answered, because no account counter moves
        // past its own limit. The host's own budget is what ends it.
        for (int attempt = 1; attempt <= limits.maxFailuresPerAddress(); attempt++) {
            mvc.perform(login(from("198.51.100.8"), "rotating-account-" + attempt, "not-the-password"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(login(from("198.51.100.8"), "rotating-account-1", "not-the-password"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aCorrectPasswordIsRefusedLikeAWrongOneOnceTheAccountIsLocked() throws Exception {
        // Or the limiter is a password oracle with a rate limit on it: "throttled
        // unless my password is right" tells an attacker it is right. The refusal
        // happens before the password is read, so this is the whole of the claim.
        lockOutAccount("198.51.100.9", PROBE_USERNAME);

        mvc.perform(login(from("198.51.100.9"), PROBE_USERNAME, PROBE_PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulLoginHandsTheAccountsBudgetBack() throws Exception {
        String address = "198.51.100.10";
        mvc.perform(login(from(address), CUSTOMER_USERNAME, "not-the-password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(login(from(address), CUSTOMER_USERNAME, "not-the-password"))
                .andExpect(status().isUnauthorized());

        mvc.perform(login(from(address), CUSTOMER_USERNAME, CUSTOMER_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Two failures and then a success, so the account has budget again: the
        // third failure is answered rather than refused. A caller who mistypes
        // their password twice and then signs in correctly is not locked out for
        // the rest of the window.
        mvc.perform(login(from(address), CUSTOMER_USERNAME, "not-the-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noRouteOtherThanTheLoginSurfaceIsThrottled() throws Exception {
        // The limiter counts logins and nothing else, so a caller that has spent
        // its login budget has not spent its way out of the rest of the platform.
        // Asserted on the management endpoint because the gateway answers that
        // itself: a 200 here cannot be a proxy to an unreachable service.
        String address = "198.51.100.11";
        for (int attempt = 1; attempt <= limits.maxFailuresPerAddress(); attempt++) {
            mvc.perform(login(from(address), "unthrottled-route-account-" + attempt, "not-the-password"));
        }
        mvc.perform(login(from(address), "unthrottled-route-account-1", "not-the-password"))
                .andExpect(status().isTooManyRequests());

        mvc.perform(get("/actuator/health").with(from(address)).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(from(address))).andExpect(status().isUnauthorized());
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Drives one account to a refused state. The configured number of guesses is
     * answered and the one after it is refused, so a lockout takes one more
     * failure than the limit allows.
     */
    private void lockOutAccount(String address, String username) throws Exception {
        for (int attempt = 1; attempt <= limits.maxFailuresPerAccount(); attempt++) {
            mvc.perform(login(from(address), username, "not-the-password"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(login(from(address), username, "not-the-password")).andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder login(RequestPostProcessor from, String username, String password) {
        return post("/auth/login")
                .with(from)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password));
    }

    /**
     * The caller the container sees. Set per request because this is the axis the
     * limiter cannot share, and the only way each of these tests can spend a
     * whole budget of its own.
     */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private String adminToken() {
        return "Bearer " + tokens.issue(44L, Set.of(Role.ADMIN)).token();
    }

    /**
     * The fields a client could act on. The timestamp differs by construction and
     * is not a signal — the same choice {@code LoginControllerTest} makes, kept
     * apart because these are two tests of the same promise at two boundaries.
     */
    private static String comparableFieldsOf(String body) throws Exception {
        var error = new ObjectMapper().readTree(body);
        return "status=" + error.get("status").asText()
                + ", error=" + error.get("error").asText()
                + ", message=" + error.get("message").asText()
                + ", path=" + error.get("path").asText();
    }
}
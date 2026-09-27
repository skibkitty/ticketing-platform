package com.raydans.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.raydans.apigateway.auth.AuthenticatedCaller;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import com.raydans.apigateway.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * What the downstream services are told about the caller.
 *
 * <p>This is the trust boundary in isolation (ADR 002). Every service in the
 * platform authorizes on the header this class writes and has no authentication
 * of its own, so the tests here are about the one property that must hold no
 * matter what arrives from outside: what a service reads is what the gateway
 * decided, never what a client sent.
 */
class UserRolesHeaderFilterTest {

    private final UserRolesHeaderFilter filter = new UserRolesHeaderFilter();

    @Test
    void anAuthenticatedCallersRolesAreForwarded() {
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs("customer", Role.CUSTOMER));

        assertThat(forwarded.getFirst(UserRolesHeaderFilter.HEADER_NAME)).isEqualTo("CUSTOMER");
    }

    @Test
    void severalRolesAreForwardedTogether() {
        HttpHeaders forwarded = apply(
                inbound(), requestAuthenticatedAs("root", Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN));

        // Comma-separated in a stable order, so a downstream that compares the
        // header as a string sees the same value for the same token every time.
        assertThat(forwarded.getFirst(UserRolesHeaderFilter.HEADER_NAME))
                .isEqualTo("ADMIN,CUSTOMER,ORGANIZER");
    }

    @Test
    void aForgedRoleHeaderFromTheClientIsOverwritten() {
        // The bypass this class exists to prevent: downstream services read
        // X-User-Roles and trust it (ADR 002), so a client arriving already
        // wearing one would be acting as anyone, with no token at all.
        HttpHeaders forwarded = apply(
                inbound(UserRolesHeaderFilter.HEADER_NAME, "ADMIN"),
                requestAuthenticatedAs("customer", Role.CUSTOMER));

        assertThat(forwarded.get(UserRolesHeaderFilter.HEADER_NAME)).containsExactly("CUSTOMER");
    }

    @Test
    void aForgedHeaderIsNotAppendedToTheRealOne() {
        // The failure mode of a naive "add the caller's roles to whatever came
        // in": ADMIN,ADMIN — which a downstream that reads only the first value
        // would believe.
        HttpHeaders forwarded = apply(
                inbound(UserRolesHeaderFilter.HEADER_NAME, "ADMIN"),
                requestAuthenticatedAs("organizer", Role.ORGANIZER));

        assertThat(forwarded.get(UserRolesHeaderFilter.HEADER_NAME)).containsExactly("ORGANIZER");
    }

    @Test
    void anUnauthenticatedRequestGetsNoRoleHeaderAtAll() {
        HttpHeaders forwarded = apply(inbound(), new MockHttpServletRequest());

        // Absent rather than empty: an empty header would be a claim that the
        // caller has no roles, and for a request that was never authenticated
        // the honest statement is that there is nothing to say.
        assertThat(forwarded.containsKey(UserRolesHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void anUnauthenticatedRequestsForgedHeaderIsNotPassedThrough() {
        // The other half of the forged-header case: with no caller to correct
        // it, the value must still not survive, or the header would be trusted
        // for exactly the requests nobody authenticated.
        HttpHeaders forwarded = apply(inbound(UserRolesHeaderFilter.HEADER_NAME, "ADMIN"), new MockHttpServletRequest());

        assertThat(forwarded.containsKey(UserRolesHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void aCallerWhoseTokenGrantsNoRolesForwardsNoRoleHeader() {
        // A token that is valid but carries nothing: the request is
        // authenticated and permitted to proceed, and the downstream learns
        // there is no role to act on rather than learning nothing happened.
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs("ghost"));

        assertThat(forwarded.containsKey(UserRolesHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void theOtherInboundHeadersAreLeftAlone() {
        // This filter's job is the caller's identity and nothing else. A filter
        // that rebuilt the whole header set would quietly drop content
        // negotiation and tracing headers on their way downstream.
        HttpHeaders inbound = inbound("Accept", "application/json", "X-Correlation-Id", "abc-123");

        HttpHeaders forwarded = apply(inbound, requestAuthenticatedAs("customer", Role.CUSTOMER));

        assertThat(forwarded.getFirst("Accept")).isEqualTo("application/json");
        assertThat(forwarded.getFirst("X-Correlation-Id")).isEqualTo("abc-123");
    }

    private HttpHeaders apply(HttpHeaders inbound, HttpServletRequest servletRequest) {
        // The converter list is required by ServerRequest.create and is never
        // read: this filter only ever touches headers.
        return filter.apply(inbound, ServerRequest.create(servletRequest, List.of()));
    }

    private static HttpHeaders inbound(String... nameValuePairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            headers.add(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return headers;
    }

    private static HttpHeaders inbound() {
        return inbound(new String[0]);
    }

    private static MockHttpServletRequest requestAuthenticatedAs(String username, Role... roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.CALLER_ATTRIBUTE, new AuthenticatedCaller(username, Set.of(roles)));
        return request;
    }
}

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
 * What the downstream services are told about which Customer is asking.
 *
 * <p>This is the identity half of the trust boundary (ADR 002), and the half
 * with more to lose: {@code X-Customer-Id} is the value
 * {@code POST /api/v1/reservations} books seats against, and the service that
 * reads it has no authentication of its own. So the property these assert is
 * the one that must hold whatever arrives from outside — a caller's id is what
 * the gateway read out of a signature it verified, never a value the client
 * sent, and never anything derived from a username.
 *
 * <p>And the second half of it: only a caller that is a Customer is given the
 * header at all. An organizer and an admin have ids, which are their own, and
 * those are not Customer ids and are not forwarded as such.
 */
class CustomerIdHeaderFilterTest {

    private final CustomerIdHeaderFilter filter = new CustomerIdHeaderFilter();

    @Test
    void theCustomersIdFromTheTokenIsForwarded() {
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs(42L, Role.CUSTOMER));

        // The case the whole filter exists for: a valid token for customer 42,
        // and the service downstream books the seats against 42.
        assertThat(forwarded.getFirst(CustomerIdHeaderFilter.HEADER_NAME)).isEqualTo("42");
    }

    @Test
    void aCustomerIdForgedByTheClientIsOverwritten() {
        // The bypass this class prevents. `curl -H "X-Customer-Id: 999"` is a
        // complete impersonation of any Customer in the platform, with no token
        // involved at all, and nothing downstream can detect it.
        HttpHeaders forwarded = apply(
                inbound(CustomerIdHeaderFilter.HEADER_NAME, "999"), requestAuthenticatedAs(42L, Role.CUSTOMER));

        assertThat(forwarded.get(CustomerIdHeaderFilter.HEADER_NAME)).containsExactly("42");
    }

    @Test
    void aForgedIdIsNotAppendedToTheRealOne() {
        // The failure mode of a naive "add the caller's id to whatever came in":
        // "999,42" — or, with the caller's own first, "42,999" — which a
        // downstream reading only the first value would believe.
        HttpHeaders forwarded = apply(
                inbound(CustomerIdHeaderFilter.HEADER_NAME, "999"), requestAuthenticatedAs(1L, Role.CUSTOMER));

        assertThat(forwarded.get(CustomerIdHeaderFilter.HEADER_NAME)).containsExactly("1");
    }

    @Test
    void anUnauthenticatedRequestGetsNoCustomerIdAtAll() {
        HttpHeaders forwarded = apply(inbound(), new MockHttpServletRequest());

        // Absent rather than empty: an empty header would be a claim that the
        // caller is a Customer with no id, and for a request nobody
        // authenticated the honest statement is that there is nothing to say.
        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void anUnauthenticatedRequestsForgedIdIsNotPassedThrough() {
        // The other half of the forged-header case: with no caller to correct
        // it, the value must still not survive, or the header would be trusted
        // for exactly the requests nobody authenticated.
        HttpHeaders forwarded = apply(
                inbound(CustomerIdHeaderFilter.HEADER_NAME, "999"), new MockHttpServletRequest());

        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void aCallerWhoIsNotACustomerIsGivenNoCustomerId() {
        // An organizer's id is its own, and publishing it as a Customer id would
        // be the gateway inventing a Customer identity the domain never gave
        // them (CONTEXT.md). There is no value that is both "the caller's id" and
        // "a Customer's id" for a caller who is not a Customer, so the header is
        // left off.
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs(43L, Role.ORGANIZER));

        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void neitherIsAnAdmins() {
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs(44L, Role.ADMIN));

        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void anOrganizersForgedCustomerIdIsStrippedRatherThanLeftToPass() {
        // The subtle one. The organizer gets no replacement, so the only thing
        // left to do with a client-supplied value is nothing at all — the strip
        // cannot be deferred to the branch that sets the header, because for
        // this caller that branch is never reached. `curl -H "X-Customer-Id: 42"`
        // as an organizer must reach the service as no header rather than as 42.
        HttpHeaders forwarded = apply(
                inbound(CustomerIdHeaderFilter.HEADER_NAME, "42"), requestAuthenticatedAs(43L, Role.ORGANIZER));

        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void aCustomerHoldingOnlyOtherRolesTooIsStillACustomer() {
        // Being a Customer is holding CUSTOMER, not holding only CUSTOMER: an
        // organizer who is also a customer buys their own seats, and the header
        // is a fact about who they are rather than a grant of anything.
        HttpHeaders forwarded = apply(
                inbound(), requestAuthenticatedAs(43L, Role.ORGANIZER, Role.CUSTOMER));

        assertThat(forwarded.getFirst(CustomerIdHeaderFilter.HEADER_NAME)).isEqualTo("43");
    }

    @Test
    void aCallerWithNoRolesAtAllIsNotACustomer() {
        // The one case that reads as a lost guarantee and is not: identity and
        // authorization are separate, but being a Customer is a role in this
        // domain, so a token granting nothing grants no Customer identity. The
        // subject is still a verified caller id — this is about what may be
        // published under the Customer header, not about who the caller is.
        HttpHeaders forwarded = apply(inbound(), requestAuthenticatedAs(42L));

        assertThat(forwarded.containsKey(CustomerIdHeaderFilter.HEADER_NAME)).isFalse();
    }

    @Test
    void theOtherInboundHeadersAreLeftAlone() {
        // This filter's job is the caller's id and nothing else. A filter that
        // rebuilt the whole header set would quietly drop content negotiation
        // and tracing headers on their way downstream.
        HttpHeaders inbound = inbound("Accept", "application/json", "X-Correlation-Id", "abc-123");

        HttpHeaders forwarded = apply(inbound, requestAuthenticatedAs(42L, Role.CUSTOMER));

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

    /**
     * The request as {@link JwtAuthenticationFilter} left it: the caller it
     * authenticated, whose id came out of the token subject it verified
     * (ADR 002). Building it here by hand rather than through the filter keeps
     * this test about the header, and {@code JwtAuthenticationFilterTest} and
     * {@code GatewayProxyBootTests} about the two ends of it.
     */
    private static MockHttpServletRequest requestAuthenticatedAs(long callerId, Role... roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.CALLER_ATTRIBUTE, new AuthenticatedCaller(callerId, Set.of(roles)));
        return request;
    }
}

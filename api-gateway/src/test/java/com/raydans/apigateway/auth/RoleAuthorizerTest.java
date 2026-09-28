package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.raydans.apigateway.auth.Role;
import com.raydans.apigateway.auth.RoleAuthorizer.Access;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The authorization table, read as a table.
 *
 * <p>These assert the policy in isolation from the filter that enforces it,
 * because the two ways that can go wrong are different. A wrong rule here is a
 * policy bug and is obvious in the table; a filter that never asks is an
 * enforcement bug and is invisible in the table — which is why
 * {@code JwtAuthenticationFilterTest} exists alongside this and checks that a
 * refusal actually happens over HTTP.
 */
class RoleAuthorizerTest {

    private final RoleAuthorizer authorizer = new RoleAuthorizer();

    @Test
    void loginIsTheOnlyRouteThatNeedsNoToken() {
        assertThat(authorizer.decide("POST", "/auth/login")).isInstanceOf(Access.Public.class);
    }

    @Test
    void theRestOfTheAuthPathIsNotPublicJustBecauseLoginIs() {
        // The rule is the endpoint, not the prefix. "/auth/**" would make every
        // route added under /auth from here on public without anyone deciding
        // so, and the next ones to land there are a refresh and a
        // password-reset endpoint.
        for (String path : List.of("/auth/foo", "/auth/token", "/auth/register", "/auth/")) {
            assertThat(authorizer.decide("GET", path))
                    .as("GET %s", path)
                    .isInstanceOf(Access.AnyAuthenticated.class);
            assertThat(authorizer.decide("POST", path))
                    .as("POST %s", path)
                    .isInstanceOf(Access.AnyAuthenticated.class);
        }
    }

    @Test
    void theLoginEndpointIsOnlyPublicForTheMethodItAnswers() {
        // A preflight reaches the filter before a token exists, but it is
        // answered by the CORS machinery and not proxied, so narrowing the
        // login rule to POST does not put a browser out of reach.
        assertThat(authorizer.decide("GET", "/auth/login")).isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void anUnknownPathIsNotProtected() {
        // Not a hole: the gateway serves no such path, so the answer is a 404.
        // Protecting it would only turn a typo in a URL into a misleading 401.
        assertThat(authorizer.decide("GET", "/nothing/here")).isInstanceOf(Access.Public.class);
    }

    @Test
    void readingAnEventNeedsATokenButNoParticularRole() {
        assertThat(authorizer.decide("GET", "/api/v1/events"))
                .isInstanceOf(Access.AnyAuthenticated.class);
        assertThat(authorizer.decide("GET", "/api/v1/events/42/seats"))
                .isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void creatingAnEventIsTheOrganizersToDo() {
        Access decision = authorizer.decide("POST", "/api/v1/events");

        assertThat(decision).isInstanceOf(Access.AnyOfRoles.class);
        assertThat(((Access.AnyOfRoles) decision).roles())
                .containsExactlyInAnyOrder(Role.ORGANIZER, Role.ADMIN);
    }

    @Test
    void readingAnEventIsNotRestrictedTheWayCreatingOneIs() {
        // The same path, a different method. If this ever matched the POST rule
        // the platform would stop being browsable, which is the failure a
        // careless "any /api/v1/events" rule would introduce.
        assertThat(authorizer.decide("GET", "/api/v1/events")).isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void reservationsAreTheCustomersAlone() {
        // A Reservation belongs to a Customer and cannot be recorded without one,
        // so this is not "any authenticated role". An organizer's token gets no
        // X-Customer-Id either, so opening this to organizers would mean a
        // reservation booked against no Customer at all.
        assertThat(authorizer.decide("POST", "/api/v1/reservations"))
                .isEqualTo(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)));
        assertThat(authorizer.decide("GET", "/api/v1/reservations/7"))
                .isEqualTo(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)));
    }

    @Test
    void managementEndpointsAreTheOperatorsAlone() {
        Access decision = authorizer.decide("GET", "/actuator/health");

        assertThat(decision).isInstanceOf(Access.AnyOfRoles.class);
        assertThat(((Access.AnyOfRoles) decision).roles()).containsExactly(Role.ADMIN);
    }

    @Test
    void anActuatorSubpathIsStillGated() {
        // "/actuator" alone would pass a startsWith check; the pattern must not.
        assertThat(authorizer.decide("GET", "/actuator/env"))
                .isInstanceOf(Access.AnyOfRoles.class);
    }

    @Test
    void paymentsNeedATokenLikeEverythingElseUnderTheApi() throws Exception {
        assertThat(authorizer.decide("GET", "/api/v1/payments/9"))
                .isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void aCustomersInboxIsTheCustomersAndNotAnyAuthenticatedCaller() throws Exception {
        // Notifications are no longer on the /api/** catch-all. The service would
        // refuse a non-Customer anyway, since it scopes the read by identity and an
        // organizer has none — but the table says who an inbox belongs to, and a
        // catch-all answer would say it belongs to anyone holding a token (ADR 011).
        assertThat(authorizer.decide("GET", "/api/v1/notifications"))
                .isEqualTo(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)));
    }

    @Test
    void theOperatorPrefixIsAdminsAndDisjointFromACustomersOwnInbox() throws Exception {
        Access operator = new Access.AnyOfRoles(Set.of(Role.ADMIN));

        assertThat(authorizer.decide("GET", "/api/v1/admin/customers/42/notifications")).isEqualTo(operator);
        // Prefix-wide, so a route nobody has written yet is refused to a Customer
        // rather than answering on the catch-all. A path the gateway does not serve
        // is a 404, and a 403 here is the safer of the two to hand a caller who was
        // never entitled to it.
        assertThat(authorizer.decide("GET", "/api/v1/admin/not-a-route-yet")).isEqualTo(operator);
        // And nothing outside the prefix falls into it.
        assertThat(authorizer.decide("GET", "/api/v1/events")).isNotEqualTo(operator);
    }

    @Test
    void aPathThatMerelyStartsWithAProtectedOneIsNotProtected() {
        // "/api/v1/events-archive" is a different resource from
        // "/api/v1/events". Anything served under the api still needs a token,
        // so this asserts the fallback rather than claiming it is public.
        assertThat(authorizer.decide("GET", "/api/v1/events-archive"))
                .isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void creatingAnEventIsTheOrganizersToDoHoweverTheUrlIsSpelled() {
        // The bypass this closes: a rule for the bare "/api/v1/events" does not
        // match "/api/v1/events/" or "/api/v1/events/7", so a customer could
        // create an Event through either and be refused only when they spelled
        // it the way the rule was written.
        for (String path : List.of("/api/v1/events", "/api/v1/events/", "/api/v1/events/7", "/api/v1/events/7/seats")) {
            Access decision = authorizer.decide("POST", path);

            assertThat(decision)
                    .as("POST %s", path)
                    .isInstanceOf(Access.AnyOfRoles.class);
            assertThat(((Access.AnyOfRoles) decision).roles())
                    .as("POST %s", path)
                    .containsExactlyInAnyOrder(Role.ORGANIZER, Role.ADMIN);
        }
    }

    @Test
    void aPathAddedUnderTheApiBeforeItsVersionNeedsATokenToo() {
        // "/api/**" rather than "/api/v1/**": a version that has not been
        // written yet is the case that most needs to fail closed.
        assertThat(authorizer.decide("GET", "/api/v2/events")).isInstanceOf(Access.AnyAuthenticated.class);
        assertThat(authorizer.decide("GET", "/api/anything")).isInstanceOf(Access.AnyAuthenticated.class);
    }
}

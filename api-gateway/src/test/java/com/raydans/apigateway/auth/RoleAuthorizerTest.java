package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.raydans.apigateway.auth.RoleAuthorizer.Access;
import java.util.List;
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
    void reservingSeatsIsOpenToAnyAuthenticatedRole() {
        assertThat(authorizer.decide("POST", "/api/v1/reservations"))
                .isInstanceOf(Access.AnyAuthenticated.class);
        assertThat(authorizer.decide("GET", "/api/v1/reservations/7"))
                .isInstanceOf(Access.AnyAuthenticated.class);
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
    void paymentsAndNotificationsNeedATokenLikeEverythingElseUnderTheApi() {
        assertThat(authorizer.decide("GET", "/api/v1/payments/9"))
                .isInstanceOf(Access.AnyAuthenticated.class);
        assertThat(authorizer.decide("GET", "/api/v1/notifications"))
                .isInstanceOf(Access.AnyAuthenticated.class);
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

package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.raydans.apigateway.auth.Role;
import com.raydans.apigateway.auth.RoleAuthorizer.Access;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.pattern.PathPattern;

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

    /**
     * The policy the table is checked against, not a second copy of it: the
     * browser-facing surface is /api and /auth. A row that disagrees is a row
     * whose preflight behaviour nobody decided, so it fails rather than ships.
     */
    private static final Set<String> BROWSER_FACING_PREFIXES = Set.of("/api/", "/auth/");

    /** @return whether {@code pattern} lies under one of the browser-facing prefixes */
    private static boolean browserCalls(String pattern) {
        String directory = pattern.endsWith("/**")
                ? pattern.substring(0, pattern.length() - "/**".length()) + "/"
                : pattern + "/";
        return BROWSER_FACING_PREFIXES.stream().anyMatch(directory::startsWith);
    }

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
    void anUnclassifiedPathNeedsATokenRatherThanBeingPublic() {
        // The fail-closed half. A path no row claims used to answer Public, which
        // was safe only while the table happened to cover every prefix the gateway
        // served — a property of the list rather than of the rule that mattered. Now
        // a path nobody has classified is refused like an unlisted route, so adding
        // one to application.yml without adding it here fails the build
        // (GatewayProxyBootTests walks the real route table) rather than quietly
        // publishing it.
        assertThat(authorizer.decide("GET", "/nothing/here")).isInstanceOf(Access.AnyAuthenticated.class);
        assertThat(authorizer.decide("POST", "/nothing/here")).isInstanceOf(Access.AnyAuthenticated.class);
        // And a path that is neither the api nor auth nor the management surface is
        // not answered by a typo either: "/nothing/here" and a misspelt api URL are
        // the same shape of mistake.
        assertThat(authorizer.decide("GET", "/api/v1/eventz")).isInstanceOf(Access.AnyAuthenticated.class);
    }

    @Test
    void anUnclassifiedPathIsRefusedButPublicIsStillReachableWhenARuleSaysSo() {
        // The pair, because the first alone would also be satisfied by a table with
        // no Public row at all: refusing everything would pass it. The login surface
        // is still the one thing a caller can reach with no token, and it is reachable
        // because a row claims it rather than because nothing did.
        assertThat(authorizer.isClassified("POST", "/auth/login")).isTrue();
        assertThat(authorizer.isClassified("GET", "/nothing/here")).isFalse();
        assertThat(authorizer.decide("POST", "/auth/login")).isInstanceOf(Access.Public.class);
    }

    @Test
    void loginIsTheOnlyRowThatGrantsAccessWithoutAToken() {
        // Read off the table rather than off the public API, because the public API
        // cannot answer it: a Public answer and no answer at all are the same claim
        // made about a path, and only the rows can say which of them a path got. If
        // somebody adds a second Public row, a second surface is being published and
        // this is where it should have to be said out loud.
        List<String> grantedWithoutAToken = RoleAuthorizer.rules().stream()
                .filter(rule -> rule.access() instanceof Access.Public)
                .flatMap(rule -> rule.paths().stream())
                .map(pattern -> pattern.getPatternString())
                .toList();

        assertThat(grantedWithoutAToken).containsExactly("/auth/login");
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
    void theBrowserSurfaceIsWhereAPreflightIsAnsweredWithoutAToken() {
        // The routes a browser application actually calls: it signs in over /auth
        // and then talks to /api. Answering a preflight there without a token is
        // what makes CORS work — the browser has no token yet.
        assertThat(authorizer.isPreflightExempt("/auth/login")).isTrue();
        assertThat(authorizer.isPreflightExempt("/api/v1/events")).isTrue();
        assertThat(authorizer.isPreflightExempt("/api/v1/admin/customers/42/notifications")).isTrue();
    }

    @Test
    void theManagementEndpointsAreNotPartOfTheBrowserSurface() {
        // A preflight to /actuator/** takes the ordinary authorization path, so
        // "/actuator/** is ADMIN's" is a statement about the route rather than
        // about the route for every verb but OPTIONS. No browser runs against
        // the management endpoints.
        assertThat(authorizer.isPreflightExempt("/actuator/health")).isFalse();
        assertThat(authorizer.isPreflightExempt("/actuator/env")).isFalse();
        // "/actuator" alone would pass a startsWith check; the pattern must not.
        assertThat(authorizer.isPreflightExempt("/actuator")).isFalse();
    }

    @Test
    void aPreflightExemptionIsNotAPathPrefixMistake() {
        // A path that merely starts with a browser-facing one is not on the
        // browser surface: "/api-internal/..." is a different resource, and
        // nothing has said a browser calls it.
        assertThat(authorizer.isPreflightExempt("/api-internal/metrics")).isFalse();
        assertThat(authorizer.isPreflightExempt("/authentic/health")).isFalse();
    }

    @Test
    void everyRowSaysWhetherABrowserCallsIt() {
        // The drift guard. Authorization and preflight are read off one table, so
        // a new row has to declare this rather than default into it. The policy —
        // the browser-facing surface is /api and /auth — is stated here, once, and
        // a row that disagrees with it fails the build.
        for (RoleAuthorizer.Rule rule : RoleAuthorizer.rules()) {
            for (PathPattern path : rule.paths()) {
                String pattern = path.getPatternString();
                assertThat(rule.browserFacing()).as("%s", pattern).isEqualTo(browserCalls(pattern));
            }
        }
    }

    @Test
    void preflightExemptionFollowsTheRowThatAuthorizesTheRoute() {
        // The same guard read through the public API and the real matcher, so it
        // also catches the flag being right on a row that a more specific row
        // shadows — a row's answer being correct but never consulted.
        for (RoleAuthorizer.Rule rule : RoleAuthorizer.rules()) {
            for (PathPattern path : rule.paths()) {
                String pattern = path.getPatternString();
                String probe = pattern.endsWith("/**") ? pattern + "probe" : pattern;
                assertThat(authorizer.isPreflightExempt(probe))
                        .as("%s", pattern)
                        .isEqualTo(rule.browserFacing());
            }
        }
    }

    @Test
    void theBrowserSurfaceIsTheWholeSubtreeAndNotOnlyItsDeeperPaths() {
        // Spring's PathPattern rather than a prefix check, so the edges are worth
        // pinning: "/api/**" covers the bare "/api" and "/api/" as well as the
        // versioned paths, and no rule here relies on the bare form matching a
        // trailing slash the way "/api/v1/events" would not.
        for (String path : List.of("/api", "/api/", "/api/v1", "/api/v1/", "/api/v1/events", "/auth", "/auth/", "/auth/login")) {
            assertThat(authorizer.isPreflightExempt(path)).as(path).isTrue();
        }
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

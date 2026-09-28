package com.raydans.apigateway.auth;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * What a request is allowed to do without looking at who is asking, as a table
 * rather than as control flow buried in a filter chain.
 *
 * <p>Matched most-specific-first, first match wins. That is what lets
 * {@code POST /api/v1/events} be organizers-only while {@code /api/**} underneath
 * it stays open to any authenticated caller — so the order is asserted in
 * {@code RoleAuthorizerTest} rather than left to whoever edits it next.
 *
 * <p>An unmatched path is {@link Access#Public}, which is safe only because the
 * table closes every prefix the gateway serves: {@code /api/**} and
 * {@code /auth/**} both end the list, so a new route cannot be reachable
 * without a token just by not being written down yet. What is left over is a
 * path this gateway does not serve, where the answer is a 404 — and a 401 on a
 * URL nobody serves would only turn a typo into a misleading error.
 */
public class RoleAuthorizer {

    /** The rules, most specific first. See the class comment for why the order matters. */
    private static final List<Rule> RULES = List.of(
            // The login surface authenticates callers rather than authorizing
            // them, so it alone is open. The rule is the endpoint, not the
            // prefix: "/auth/**" would make every future route added under
            // /auth public by default, and the next one added there would be a
            // session-refresh or a password-reset endpoint that nobody decided
            // to expose. Unmatched paths are public anyway, so this is not what
            // stops those — the rule below is.
            new Rule(new Access.Public(), HttpMethod.POST, Set.of("/auth/login")),
            // Everything else under /auth, which is where anything added later
            // lands. A token of any role: a future /auth route that ought to be
            // narrower is then a change to make deliberately, rather than a
            // privilege granted by its not having been written down yet.
            new Rule(new Access.AnyAuthenticated(), null, Set.of("/auth/**")),
            // Management endpoints are the platform operator's, kept off every
            // other caller's reach.
            new Rule(new Access.AnyOfRoles(Set.of(Role.ADMIN)), null, Set.of("/actuator/**")),
            // A Seat is the inventory, so who may add one is the same question as
            // who owns the Event. Reads stay open to any authenticated caller,
            // because a Customer has to be able to browse before buying.
            //
            // Both the Event and everything under it: the bare pattern matches
            // neither "/api/v1/events/" nor "/api/v1/events/7", and a rule
            // listing only it would let a customer create an Event through a
            // trailing slash while refusing the other spelling.
            new Rule(
                    new Access.AnyOfRoles(Set.of(Role.ORGANIZER, Role.ADMIN)),
                    HttpMethod.POST,
                    Set.of("/api/v1/events", "/api/v1/events/**")),
            // A Reservation belongs to a Customer by definition, and
            // ReservationController cannot record one without a Customer id
            // (CONTEXT.md) — so this is Customer-only rather than open to any
            // authenticated role. An organizer that wanted to buy a seat would
            // need a Customer identity, which is a domain question and not one
            // this table should answer by quietly treating it as one.
            new Rule(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)), null, Set.of("/api/v1/reservations/**")),
            // A Customer's inbox, and Customers only. The service scopes this read by
            // the caller's identity rather than by anything the client sends, so
            // there is no wider case to allow here — an organizer has no Customer
            // identity to have an inbox under, and admitting it would forward a
            // request that arrives with no id and is refused a hop later. Said here
            // instead so the reason is visible in the table (ADR 011).
            new Rule(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)), null, Set.of("/api/v1/notifications/**")),
            // The operator's own routes, kept under one prefix so "a route for looking
            // at somebody else's data" is a visible, deliberate thing to add rather
            // than an exception scattered through the table. This is the only place
            // ADMIN's reach over another Customer's data is decided, and it is the
            // deliberate counterpart to the rule above: the two are disjoint, so
            // reaching the operator's read requires giving up the self-service one,
            // and only a caller holding both roles gets both (ADR 011).
            new Rule(new Access.AnyOfRoles(Set.of(Role.ADMIN)), null, Set.of("/api/v1/admin/**")),
            // Everything else under /api, so a path added before its version does
            // not start life public.
            new Rule(new Access.AnyAuthenticated(), null, Set.of("/api/**")));

    private final PathPatternParser parser = new PathPatternParser();

    /** @return what the gateway requires of a caller for this request, never null */
    public Access decide(String method, String path) {
        PathContainer candidate = PathContainer.parsePath(path);
        for (Rule rule : RULES) {
            if (rule.matches(parser, method, candidate)) {
                return rule.access();
            }
        }
        return new Access.Public();
    }

    /**
     * One row of the table: which requests it covers, and what they require.
     *
     * <p>The requirement is an {@link Access} rather than a role set, because
     * "no token needed" and "a token of any role" are different answers that a
     * role set alone cannot tell apart.
     *
     * @param access  what a caller must have
     * @param method  the single method this row covers, or null for any
     * @param paths   the path patterns this row covers
     */
    private record Rule(Access access, HttpMethod method, Set<String> paths) {

        boolean matches(PathPatternParser parser, String requestMethod, PathContainer path) {
            if (method != null && !method.name().equalsIgnoreCase(requestMethod)) {
                return false;
            }
            return paths.stream().anyMatch(pattern -> parser.parse(pattern).matches(path));
        }
    }

    /**
     * What a request needs before the gateway will forward it. Sealed because
     * these are the three genuinely different outcomes, and the filter's two
     * {@code instanceof} tests should stop compiling the day a fourth is added
     * and someone has to decide what it means.
     */
    public sealed interface Access permits Access.Public, Access.AnyAuthenticated, Access.AnyOfRoles {

        /** No token needed. The login surface, and paths this gateway does not serve. */
        record Public() implements Access {}

        /** A valid token, of any role. */
        record AnyAuthenticated() implements Access {}

        /** A valid token carrying at least one of these roles. */
        record AnyOfRoles(Set<Role> roles) implements Access {
            public AnyOfRoles {
                roles = Set.copyOf(roles);
            }
        }
    }
}

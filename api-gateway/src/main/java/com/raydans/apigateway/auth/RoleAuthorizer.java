package com.raydans.apigateway.auth;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
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
 * <p>An unmatched path is {@link Access#AnyAuthenticated}, so a path nobody
 * wrote down here is refused rather than open: {@link Access#Public} is
 * reachable only through a row that says so, which today is {@code POST
 * /auth/login} and nothing else. Being reachable is a thing somebody decides,
 * not a thing a route acquires by not having been written down — the difference
 * being that the safe answer is also the one that needs no argument for why the
 * table happens to cover every prefix.
 *
 * <p>The cost is that a caller who misspells a URL is answered 401 rather than
 * 404. That is the smaller mistake: it is the answer for a request that cannot
 * be served without saying who the caller would have to be, it tells a caller
 * with a token nothing they did not already know, and it only ever over-refuses.
 * {@code GatewayProxyBootTests} walks the deployment's real route table and
 * fails the build if a route is served without a row here, so the paths a
 * genuine 404 is for are the ones this gateway does not have.
 *
 * <p>Two questions are asked of this table — what a request requires
 * ({@link #decide}), and whether a CORS preflight is answered without a token
 * ({@link #isPreflightExempt}) — and it is one table answering both, so a route
 * cannot be added to one and forgotten in the other.
 */
public class RoleAuthorizer {

    /** The rules, most specific first. See the class comment for why the order matters. */
    private static final List<Rule> RULES = List.of(
            // The login surface authenticates callers rather than authorizing
            // them, so it alone is open — and open because this row says so, not
            // because it matched nothing. The rule is the endpoint, not the
            // prefix: "/auth/**" would make every future route added under
            // /auth public without anyone deciding so, and the next one added
            // there would be a session-refresh or a password-reset endpoint that
            // nobody decided to expose. The rule below is what stops those.
            Rule.of(new Access.Public(), HttpMethod.POST, Set.of("/auth/login"), true),
            // Everything else under /auth, which is where anything added later
            // lands. A token of any role: a future /auth route that ought to be
            // narrower is then a change to make deliberately, rather than a
            // privilege granted by its not having been written down yet.
            Rule.of(new Access.AnyAuthenticated(), null, Set.of("/auth/**"), true),
            // Management endpoints are the platform operator's, kept off every
            // other caller's reach.
            Rule.of(new Access.AnyOfRoles(Set.of(Role.ADMIN)), null, Set.of("/actuator/**"), false),
            // A Seat is the inventory, so who may add one is the same question as
            // who owns the Event. Reads stay open to any authenticated caller,
            // because a Customer has to be able to browse before buying.
            //
            // Both the Event and everything under it: the bare pattern matches
            // neither "/api/v1/events/" nor "/api/v1/events/7", and a rule
            // listing only it would let a customer create an Event through a
            // trailing slash while refusing the other spelling.
            Rule.of(
                    new Access.AnyOfRoles(Set.of(Role.ORGANIZER, Role.ADMIN)),
                    HttpMethod.POST,
                    Set.of("/api/v1/events", "/api/v1/events/**"),
                    true),
            // A Reservation belongs to a Customer by definition, and
            // ReservationController cannot record one without a Customer id
            // (CONTEXT.md) — so this is Customer-only rather than open to any
            // authenticated role. An organizer that wanted to buy a seat would
            // need a Customer identity, which is a domain question and not one
            // this table should answer by quietly treating it as one.
            Rule.of(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)), null, Set.of("/api/v1/reservations/**"), true),
            // A Customer's inbox, and Customers only. The service scopes this read by
            // the caller's identity rather than by anything the client sends, so
            // there is no wider case to allow here — an organizer has no Customer
            // identity to have an inbox under, and admitting it would forward a
            // request that arrives with no id and is refused a hop later. Said here
            // instead so the reason is visible in the table (ADR 011).
            Rule.of(new Access.AnyOfRoles(Set.of(Role.CUSTOMER)), null, Set.of("/api/v1/notifications/**"), true),
            // The operator's own routes, kept under one prefix so "a route for looking
            // at somebody else's data" is a visible, deliberate thing to add rather
            // than an exception scattered through the table. This is the only place
            // ADMIN's reach over another Customer's data is decided, and it is the
            // deliberate counterpart to the rule above: the two are disjoint, so
            // reaching the operator's read requires giving up the self-service one,
            // and only a caller holding both roles gets both (ADR 011).
            Rule.of(new Access.AnyOfRoles(Set.of(Role.ADMIN)), null, Set.of("/api/v1/admin/**"), true),
            // Everything else under /api, so a path added before its version does
            // not start life public. Still a row rather than a note on the
            // fallback, because the fallback refuses a preflight and this answers
            // it: dropping the row would not only narrow authorization, it would
            // quietly break every browser (ADR 002).
            Rule.of(new Access.AnyAuthenticated(), null, Set.of("/api/**"), true));

    /**
     * Read by {@code RoleAuthorizerTest} to assert the table is internally
     * consistent. Package-private rather than private so that check is a test of
     * the table itself and not a restatement of it.
     */
    static List<Rule> rules() {
        return RULES;
    }

    /**
     * @return what the gateway requires of a caller for this request, never null.
     *     A path no row claims gets {@link Access#AnyAuthenticated}: the refusal is
     *     the default so that forgetting to write a row down opens nothing.
     */
    public Access decide(String method, String path) {
        return ruleFor(method, path).map(Rule::access).orElseGet(Access.AnyAuthenticated::new);
    }

    /**
     * Whether a row claims this request at all, as opposed to {@link #decide}
     * falling back to what it answers for a path nobody has classified.
     *
     * <p>That distinction is invisible through {@code decide} alone — both a row
     * saying "any token will do" and no row at all answer the same way — and it is
     * the question only a deployment can answer: whether the routes
     * {@code application.yml} actually serves are written down here. Without this,
     * the table's own tests pass on a table that has quietly stopped covering a
     * route somebody added.
     */
    public boolean isClassified(String method, String path) {
        return ruleFor(method, path).isPresent();
    }

    /**
     * Whether a CORS preflight to this path is answered without a token. A
     * property of the route, not of the method: a browser sends the handshake
     * before it has a token and never sends one with it, and only for the routes
     * it actually calls (ADR 002).
     */
    public boolean isPreflightExempt(String path) {
        return ruleFor(HttpMethod.OPTIONS.name(), path).map(Rule::browserFacing).orElse(false);
    }

    private static Optional<Rule> ruleFor(String method, String path) {
        PathContainer candidate = PathContainer.parsePath(path);
        return RULES.stream().filter(rule -> rule.matches(method, candidate)).findFirst();
    }

    /**
     * One row of the table: which requests it covers, what they require, and
     * whether a browser calls them.
     *
     * <p>The requirement is an {@link Access} rather than a role set, because
     * "no token needed" and "a token of any role" are different answers that a
     * role set alone cannot tell apart.
     *
     * <p>{@code browserFacing} is asked here rather than answered by a second
     * list of its own, so a route cannot be added to one and forgotten in the
     * other. It is a required argument rather than a default, because either
     * default a new row could silently take is wrong in one direction or the
     * other: one breaks a browser, the other opens a route.
     *
     * @param access  what a caller must have
     * @param method  the single method this row covers, or null for any
     * @param paths   the path patterns this row covers
     */
    record Rule(Access access, HttpMethod method, List<PathPattern> paths, boolean browserFacing) {

        /** Parsed once, at class-initialisation, rather than again for each request. */
        static Rule of(Access access, HttpMethod method, Set<String> patterns, boolean browserFacing) {
            PathPatternParser parser = new PathPatternParser();
            return new Rule(access, method, patterns.stream().map(parser::parse).toList(), browserFacing);
        }

        boolean matches(String requestMethod, PathContainer path) {
            if (method != null && !method.name().equalsIgnoreCase(requestMethod)) {
                return false;
            }
            return paths.stream().anyMatch(pattern -> pattern.matches(path));
        }
    }

    /**
     * What a request needs before the gateway will forward it. Sealed because
     * these are the three genuinely different outcomes, and the filter's two
     * {@code instanceof} tests should stop compiling the day a fourth is added
     * and someone has to decide what it means.
     */
    public sealed interface Access permits Access.Public, Access.AnyAuthenticated, Access.AnyOfRoles {

        /**
         * No token needed. Reachable only through a row that says so: the login
         * surface, and nothing else.
         */
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

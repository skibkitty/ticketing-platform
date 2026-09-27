package com.raydans.apigateway.auth;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides what a request to a given method and path is allowed to do without
 * looking at who is asking. Separated from the filter that enforces it so the
 * policy can be read, and tested, as a table rather than as control flow buried
 * in a filter chain.
 *
 * <p>Rules are matched most-specific-first and the first match wins, which is
 * what lets {@code POST /api/v1/events} be ORGANIZER-only while
 * {@code /api/**} underneath it stays open to any authenticated caller. An
 * "allow" rule and a "restrict" rule both have to exist, so an ordering bug
 * would fail in the direction that leaks — the reason the order below is written
 * as a table and asserted in {@code RoleAuthorizerTest} rather than left to
 * whoever edits it next.
 *
 * <p>Anything not matched by a rule is {@link Access#Public}. A new route is
 * therefore reachable without a token until someone writes its rule down, which
 * is the wrong default: a new endpoint should be closed and then opened on
 * purpose. {@link #RULES} ends with a catch-all for {@code /api/**} precisely so
 * that the uncovered case is "a path this gateway does not serve", where 404 is
 * the honest answer and a permission check would only obscure it.
 */
public class RoleAuthorizer {

    /** The rules, most specific first. See the class comment for why the order matters. */
    private static final List<Rule> RULES = List.of(
            // The login surface is the one public route; it authenticates callers
            // rather than authorizing them.
            new Rule(new Access.Public(), null, Set.of("/auth/**")),
            // Management endpoints are the platform operator's, and are kept off
            // every caller's reach — including an organizer's, who manages their
            // own Events through /api/v1/events and has no business reading the
            // gateway's internals or any service's health.
            new Rule(new Access.AnyOfRoles(Set.of(Role.ADMIN)), null, Set.of("/actuator/**")),
            // Mutating an Event's inventory is the one action the domain splits
            // by role: a Seat is the inventory, so who may add one is the same
            // question as who owns the Event. Reads stay open to any
            // authenticated caller, because a Customer has to be able to browse
            // before deciding to buy.
            new Rule(
                    new Access.AnyOfRoles(Set.of(Role.ORGANIZER, Role.ADMIN)),
                    HttpMethod.POST,
                    Set.of("/api/v1/events")),
            // Holding Seats is a Customer's action, but the rule is "any
            // authenticated role" rather than CUSTOMER: an organizer demoing the
            // flow with their own token should be able to buy a seat, and
            // reserving one costs the platform nothing that reading it does not.
            new Rule(new Access.AnyAuthenticated(), null, Set.of("/api/v1/reservations/**")),
            // Everything else under /api/v1 needs a token, whatever it does.
            new Rule(new Access.AnyAuthenticated(), null, Set.of("/api/v1/**")));

    private final PathPatternParser parser = new PathPatternParser();

    /**
     * @return what the gateway requires of a caller for this request, never null
     */
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
     * <p>The requirement is carried as an {@link Access} rather than as a bare
     * role set, because "no token needed" and "a token of any role" are
     * different answers that a role set alone cannot tell apart — an empty set
     * means one of them depending on which column it is in.
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
     * these are the three genuinely different outcomes and the filter's
     * {@code switch} over them should fail to compile if a fourth is ever added
     * without a decision about what it means.
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

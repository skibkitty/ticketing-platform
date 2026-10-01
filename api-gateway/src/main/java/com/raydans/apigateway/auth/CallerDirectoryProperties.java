package com.raydans.apigateway.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The callers this gateway knows, bound from {@code app.gateway.callers.*}.
 *
 * <p>In configuration rather than a literal in source because the password is a
 * credential, and a map in source is a secret in version control. The
 * passwords are bound from the environment and shipped with no value here, so
 * the repository holds none.
 *
 * <p>The {@code caller-id} is the other half of that, and it is data rather than
 * code on purpose: the mapping from a login name to an identity is the identity
 * store's job, and ADR 002 forbids inventing one in source. A real deployment
 * replaces this whole map with a lookup at login; the token and everything
 * downstream of it are unaffected.
 *
 * <p>It is deliberately not called a customer id. Every configured caller needs
 * an id, because every token's subject is one, but a caller's id is only a
 * {@code Customer.id} when the caller holds {@code CUSTOMER} — see
 * {@link AuthenticatedCaller#isCustomer()}. Naming the field after the role
 * would assert that an organizer and an admin are Customers too, which the
 * domain does not say (CONTEXT.md).
 *
 * <p>Bound at {@code app.gateway} with the map as the {@code callers} component:
 * Boot binds a constructor-bound record's components by name, so a prefix of
 * {@code app.gateway.callers} with a component named anything else would look one
 * level deeper than the file goes and silently bind nothing.
 *
 * @param callers caller username to that caller's credential and roles
 */
@ConfigurationProperties(prefix = "app.gateway")
public record CallerDirectoryProperties(Map<String, Credentials> callers) {

    public CallerDirectoryProperties {
        callers = callers == null ? Map.of() : Map.copyOf(callers);
    }

    /**
     * @param callerId boxed so an absent value binds to null and is caught by
     *     {@link MapCallerDirectory} at startup, rather than to a 0 that would
     *     mint a token whose subject is not a caller
     * @param password supplied at runtime; a missing one is also a startup
     *     failure rather than a login that silently never succeeds
     * @param roles    role names, refused at startup if unrecognised — a typo
     *     would otherwise mint a caller who can log in and be refused by every
     *     rule
     */
    public record Credentials(Long callerId, String password, List<String> roles) {

        public Set<Role> parsedRoles() {
            return roles == null
                    ? Set.of()
                    : roles.stream().map(CallerDirectoryProperties::parseRole).collect(Collectors.toUnmodifiableSet());
        }

        /**
         * A placeholder so the flattening below stays a straight copy; the value
         * is only ever read by a directory that has already refused to start
         * without it.
         */
        long callerIdOrZero() {
            return callerId == null ? 0L : callerId;
        }
    }

    /** No callers at all, for a gateway started without configuration. */
    public static CallerDirectoryProperties empty() {
        return new CallerDirectoryProperties(Map.of());
    }

    static Role parseRole(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException(
                    "Unknown role '" + name + "'. Known roles: " + List.of(Role.values()), ex);
        }
    }

    Map<String, CallerCredentials> toCredentials() {
        Map<String, CallerCredentials> flattened = new LinkedHashMap<>();
        callers.forEach((username, account) -> flattened.put(
                username, new CallerCredentials(username, account == null ? 0L : account.callerIdOrZero(),
                        account == null ? null : account.password(),
                        account == null ? Set.of() : account.parsedRoles())));
        return flattened;
    }
}

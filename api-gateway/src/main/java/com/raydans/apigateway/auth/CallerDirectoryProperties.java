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
 * credential, and a map in source is a secret in version control.
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
     * @param roles role names, refused at startup if unrecognised — a typo would
     *     otherwise mint a caller who can log in and be refused by every rule
     */
    public record Credentials(String password, List<String> roles) {

        public Set<Role> parsedRoles() {
            return roles == null
                    ? Set.of()
                    : roles.stream().map(CallerDirectoryProperties::parseRole).collect(Collectors.toUnmodifiableSet());
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
                username, new CallerCredentials(username, account == null ? null : account.password(),
                        account == null ? Set.of() : account.parsedRoles())));
        return flattened;
    }
}

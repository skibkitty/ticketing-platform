package com.raydans.apigateway.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo caller set, bound from {@code app.gateway.users.*}.
 *
 * <p>These live in configuration rather than in a literal in source because the
 * password is a credential: a map in source is a secret in version control, and
 * rotating one would then be a code change. Configuration also means a
 * deployment can hand out its own callers without a rebuild, which is the only
 * version of this that would survive contact with anything real.
 *
 * <p>The prefix is {@code app.gateway} and the map is the {@code users} component
 * rather than the prefix being {@code app.gateway.users} with a differently named
 * component. Boot binds a constructor-bound record's components by name, so a
 * component called {@code accounts} under that prefix would look for
 * {@code app.gateway.users.accounts.customer} and bind nothing at all. The
 * indirection is invisible in the YAML and fails by leaving every caller missing,
 * so the level that does not exist in the file is not invented here.
 *
 * @param users caller username to that caller's credential and roles
 */
@ConfigurationProperties(prefix = "app.gateway")
public record UserProperties(Map<String, UserAccountProperties> users) {

    public UserProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    /**
     * @param password the credential checked at login
     * @param roles    role names, bound to {@link Role} and failing fast on an
     *     unrecognised one — a typo in a role name would otherwise mint a
     *     caller who can log in and do nothing, which reads as an auth bug
     *     reported by a user rather than a config error reported at startup
     */
    public record UserAccountProperties(String password, List<String> roles) {

        public Set<Role> parsedRoles() {
            return roles == null
                    ? Set.of()
                    : roles.stream().map(UserProperties::parseRole).collect(Collectors.toUnmodifiableSet());
        }
    }

    /** A {@link UserProperties} with no callers, for a gateway started without configuration. */
    public static UserProperties empty() {
        return new UserProperties(Map.of());
    }

    static Role parseRole(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException(
                    "Unknown role '" + name + "'. Known roles: " + List.of(Role.values()), ex);
        }
    }

    /** Flattens the bound properties into the accounts the directory serves. */
    Map<String, UserAccount> toAccounts() {
        Map<String, UserAccount> flattened = new LinkedHashMap<>();
        users.forEach((username, account) -> flattened.put(
                username, new UserAccount(username, account == null ? null : account.password(),
                        account == null ? Set.of() : account.parsedRoles())));
        return flattened;
    }
}

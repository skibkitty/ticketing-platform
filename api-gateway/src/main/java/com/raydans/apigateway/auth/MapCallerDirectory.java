package com.raydans.apigateway.auth;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The in-memory caller set, held for the life of the process.
 *
 * <p>Immutable after construction on purpose: a directory a request can write to
 * is a directory whose contents depend on request history, and a credential
 * check is only as trustworthy as the stability of what it checks against.
 */
@Component
public class MapCallerDirectory {

    private final Map<String, CallerCredentials> credentials;

    public MapCallerDirectory(CallerDirectoryProperties properties) {
        this.credentials = Map.copyOf(properties.toCredentials());
    }

    /**
     * Refuses to start with nobody in the directory, or with a caller the
     * gateway could not actually authenticate or speak for.
     *
     * <p>Otherwise configuration that fails to bind is a gateway that answers
     * every login with 401 and no other symptom, and the operator goes looking
     * at credentials that were never read. The passwords are supplied at
     * runtime, so a deployment that forgets one is the common case rather than
     * the exotic one; and a caller with no id of its own is worse still, because
     * a token could be minted for it whose subject is not a caller and every
     * request made with it is refused for a reason that has nothing to do with
     * the caller.
     *
     * <p>On initialization rather than in the constructor so a test can still
     * build the empty directory it means to test.
     */
    @PostConstruct
    void refuseToStartWithUnusableCallers() {
        if (credentials.isEmpty()) {
            throw new IllegalStateException(
                    "No callers are configured under app.gateway.callers. The gateway would reject every login."
                            + " Expected entries like 'app.gateway.callers.customer.caller-id'.");
        }
        credentials.forEach((username, account) -> {
            if (account.callerId() < 1) {
                throw new IllegalStateException(
                        "Caller '" + username + "' has no usable caller-id. The token's subject is the caller's own"
                                + " id (ADR 002), so the gateway cannot authenticate anyone as this login name"
                                + " without one. This is required of every caller, not only of CUSTOMER ones:"
                                + " an organizer's id is its own and is never published as a Customer. Expected"
                                + " 'app.gateway.callers." + username + ".caller-id'.");
            }
            if (account.password() == null || account.password().isBlank()) {
                throw new IllegalStateException(
                        "Caller '" + username + "' has no password. Passwords are supplied at runtime and no"
                                + " default ships, so nothing here is a usable credential by omission. Expected"
                                + " 'app.gateway.callers." + username + ".password' to be set from the"
                                + " environment.");
            }
        });
    }

    /**
     * @return the credentials for this name, or empty when there is no such
     *     caller — which the login surface must not distinguish from a wrong
     *     password, or it becomes an account-existence oracle
     */
    public Optional<CallerCredentials> findByUsername(String username) {
        return Optional.ofNullable(credentials.get(username));
    }
}

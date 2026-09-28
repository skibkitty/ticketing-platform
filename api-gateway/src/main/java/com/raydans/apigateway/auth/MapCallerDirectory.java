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
     * Refuses to start with nobody in the directory.
     *
     * <p>Otherwise configuration that fails to bind is a gateway that answers
     * every login with 401 and no other symptom, and the operator goes looking
     * at credentials that were never read.
     *
     * <p>On initialization rather than in the constructor so a test can still
     * build the empty directory it means to test.
     */
    @PostConstruct
    void refuseToStartWithNoCallers() {
        if (credentials.isEmpty()) {
            throw new IllegalStateException(
                    "No callers are configured under app.gateway.callers. The gateway would reject every login."
                            + " Expected entries like 'app.gateway.callers.customer.password'.");
        }
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

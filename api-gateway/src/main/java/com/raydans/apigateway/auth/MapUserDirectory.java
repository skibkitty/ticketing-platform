package com.raydans.apigateway.auth;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The in-memory directory: the whole caller set, held for the life of the
 * process.
 *
 * <p>Immutable after construction on purpose. A directory a request can write
 * to is a directory whose contents depend on request history, and a credential
 * check is only as trustworthy as the stability of what it checks against.
 * There is no registration surface, so nothing can add a caller at runtime.
 */
@Component
public class MapUserDirectory implements UserDirectory {

    private final Map<String, UserAccount> accounts;

    public MapUserDirectory(UserProperties properties) {
        this.accounts = Map.copyOf(properties.toAccounts());
    }

    /**
     * Refuses to start with nobody in the directory.
     *
     * <p>Without this, a configuration that fails to bind is a gateway that
     * answers every login with 401 and no other symptom: the request looks like a
     * caller with the wrong password, and the operator goes looking at credentials
     * that were never read. A wrong key in the YAML — one missing level, one
     * typo — is the likely cause, and it is worth a startup failure over a
     * platform nobody can log in to.
     *
     * <p>On initialization rather than in the constructor so that a test can still
     * build the empty directory it means to test.
     */
    @PostConstruct
    void refuseToStartWithNoCallers() {
        if (accounts.isEmpty()) {
            throw new IllegalStateException(
                    "No callers are configured under app.gateway.users. The gateway would reject every login."
                            + " Expected entries like 'app.gateway.users.customer.password'.");
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(accounts.get(username));
    }
}

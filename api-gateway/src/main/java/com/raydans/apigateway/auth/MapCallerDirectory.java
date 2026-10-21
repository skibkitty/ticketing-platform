package com.raydans.apigateway.auth;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
     * Refuses to start with nobody in the directory, with a caller the gateway
     * could not actually authenticate or speak for, or with two callers claiming
     * one identity.
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
        refuseToStartWithSharedCallerIds();
    }

    /**
     * Refuses to start with two login names claiming one identity.
     *
     * <p>A caller's id is the token's subject and the subject is what every
     * downstream identity is derived from (ADR 002), so the mapping from a
     * caller id to a caller has to be a function. Two callers configured with
     * the same {@code caller-id} both authenticate, both are issued a token
     * carrying {@code sub=42}, and nothing after the login can tell the two
     * apart: bob's token is alice's token, so a Customer's reservations, inbox
     * and payments would belong to whoever happens to have that number.
     *
     * <p>Worse, it would not look like a bug. Both logins work, every route
     * answers, and the symptom is data attributed to the wrong person — found
     * later, by a Customer. The other half of the answer is that a duplicate
     * cannot be resolved by picking a winner: the gateway has no way to know
     * which of the two was meant, and choosing one would hand one login another
     * caller's identity, which is the very thing being prevented here. So it
     * refuses to start.
     *
     * <p>After the per-caller checks above, so a caller with no id of its own is
     * reported as that rather than as two callers colliding on the placeholder
     * 0 that {@code callerIdOrZero()} substitutes.
     */
    private void refuseToStartWithSharedCallerIds() {
        Map<Long, List<String>> usernamesByCallerId = new TreeMap<>();
        credentials.forEach((username, account) ->
                usernamesByCallerId.computeIfAbsent(account.callerId(), id -> new ArrayList<>()).add(username));

        usernamesByCallerId.forEach((callerId, usernames) -> {
            if (usernames.size() > 1) {
                // Sorted because the credentials map has no order of its own:
                // the same configuration must produce the same message, or a
                // flaky message becomes the next thing nobody trusts.
                String callers = usernames.stream().sorted().collect(Collectors.joining("', '"));
                throw new IllegalStateException(
                        "Callers '" + callers + "' are all configured with caller-id " + callerId + ". The token's"
                                + " subject is the caller's own id (ADR 002), so each of them would be issued a token"
                                + " with sub=" + callerId + " and every service downstream would treat them as one"
                                + " identity: a request made as one caller would act as the other. Every entry under"
                                + " app.gateway.callers needs its own caller-id, and the gateway will not choose"
                                + " between two callers for you.");
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

package com.raydans.apigateway.auth;

import java.util.Optional;

/**
 * Where the gateway looks up a caller's credential. A seam rather than a
 * concrete class so the login surface can be exercised without a directory,
 * and so a real identity provider (explicitly out of scope) could replace the
 * in-memory one without the auth filter noticing.
 */
public interface UserDirectory {

    /**
     * @return the account with this username, or empty when there is no such
     *     caller — which the login surface must not distinguish from a wrong
     *     password, or it becomes an account-existence oracle.
     */
    Optional<UserAccount> findByUsername(String username);
}

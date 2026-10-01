package com.raydans.apigateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one route on the gateway that is deliberately unauthenticated: how a
 * caller becomes authenticated in the first place. Hands out a token and nothing
 * else — no session, no state, no downstream call.
 *
 * <p>The limit on guessing lives here rather than in a filter or in the gateway
 * proxy config, and the reason is that only this method can tell a failed guess
 * from a successful one. A filter sees requests and counts them, which answers
 * "how many attempts arrived" and not "how many were wrong" — so it would spend a
 * real caller's budget on their sign-in, and it would have no way to know that a
 * login which succeeded should hand the account its budget back. Being the
 * controller is what makes the counter mean something (ADR 015).
 */
@RestController
@RequestMapping("/auth")
public class LoginController {

    private final MapCallerDirectory directory;
    private final JwtService tokens;
    private final LoginThrottle throttle;

    public LoginController(MapCallerDirectory directory, JwtService tokens, LoginThrottle throttle) {
        this.directory = directory;
        this.tokens = tokens;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    LoginResponse login(HttpServletRequest request, @Valid @RequestBody LoginRequest credentials) {
        // The remote address is read here and nowhere else, and no forwarded
        // header is consulted (ADR 015): this platform has one hop and trusts
        // nothing, so a forwarded header is the caller's own.
        String remoteAddress = request.getRemoteAddr();

        // Asked first, before the password is read at all: a caller over the limit
        // learns nothing from being refused, which a correct password must not
        // change or the limit becomes a password oracle with a rate limit on it.
        throttle.refuseWhenThrottled(remoteAddress, credentials.username());

        CallerCredentials account = directory
                .findByUsername(credentials.username())
                .filter(candidate -> secretsMatch(candidate.password(), credentials.password()))
                .orElse(null);

        if (account == null) {
            // Counted and then refused or not, inside the throttle: an unknown
            // username is counted exactly as a wrong password is, which is what
            // stops the counter itself from disclosing which login names exist.
            throttle.recordFailure(remoteAddress, credentials.username());
            throw new InvalidCredentialsException();
        }

        // The account's own budget back, and only the account's — see
        // LoginThrottle#recordSuccess.
        throttle.recordSuccess(credentials.username());

        // The subject is the identity this login name belongs to, not the login
        // name: everything the gateway tells a downstream service about the
        // caller is derived from the subject (ADR 002). The username does its job
        // here and goes no further. Note that for an organizer this is the
        // organizer's own id, not a Customer's — it becomes a Customer id only
        // for a caller that holds CUSTOMER, and is published as one only then.
        IssuedToken issued = tokens.issue(account.callerId(), account.roles());
        return new LoginResponse(
                issued.token(),
                Role.namesOf(account.roles()),
                issued.expiresAt());
    }

    /**
     * Constant-time on purpose: a compare that stops at the first differing
     * character takes a time that is a function of how much of the password was
     * right, which recovers a password one character at a time from an
     * unauthenticated endpoint.
     */
    private static boolean secretsMatch(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
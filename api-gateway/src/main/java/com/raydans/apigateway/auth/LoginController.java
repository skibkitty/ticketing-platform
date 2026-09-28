package com.raydans.apigateway.auth;

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
 */
@RestController
@RequestMapping("/auth")
public class LoginController {

    private final MapCallerDirectory directory;
    private final JwtService tokens;

    public LoginController(MapCallerDirectory directory, JwtService tokens) {
        this.directory = directory;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        CallerCredentials account = directory
                .findByUsername(request.username())
                .filter(candidate -> secretsMatch(candidate.password(), request.password()))
                .orElseThrow(InvalidCredentialsException::new);

        IssuedToken issued = tokens.issue(account.username(), account.roles());
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

package com.raydans.apigateway.auth;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one route on the gateway that is deliberately unauthenticated: it is how a
 * caller becomes authenticated in the first place.
 *
 * <p>This controller is the entire reason the gateway is the only module that
 * carries a security dependency. It hands out a token and nothing else — it
 * creates no session, keeps no state, and never sees a downstream service.
 */
@RestController
@RequestMapping("/auth")
public class LoginController {

    private final UserDirectory directory;
    private final JwtService tokens;

    public LoginController(UserDirectory directory, JwtService tokens) {
        this.directory = directory;
        this.tokens = tokens;
    }

    /**
     * Exchanges a username and password for a token.
     *
     * <p>The comparison is constant-time. A byte-by-byte string compare stops at
     * the first differing character, so the time it takes becomes a function of
     * how much of the password was right — a slow and entirely unauthenticated
     * way to recover a password one character at a time. Avoiding that costs
     * nothing here, so it is avoided.
     */
    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserAccount account = directory
                .findByUsername(request.username())
                .filter(candidate -> secretsMatch(candidate.password(), request.password()))
                .orElseThrow(InvalidCredentialsException::new);

        IssuedToken issued = tokens.issue(account.username(), account.roles());
        return new LoginResponse(
                issued.token(),
                account.roles().stream().map(Enum::name).sorted().toList(),
                issued.expiresAt());
    }

    private static boolean secretsMatch(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}

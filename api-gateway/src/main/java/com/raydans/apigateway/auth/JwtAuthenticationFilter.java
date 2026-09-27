package com.raydans.apigateway.auth;

import com.raydans.apigateway.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The platform's authentication and authorization boundary (ADR 002): it turns
 * a {@code Authorization: Bearer} header into an {@link AuthenticatedCaller},
 * decides whether the caller may make this request, and puts the result where
 * the proxy can see it.
 *
 * <p>A servlet filter rather than a controller or a gateway route filter,
 * because it has to run for paths that are not routed at all —
 * {@code /actuator/**} most of all, which is a container route the proxy never
 * sees. A rule that only runs on proxied requests would leave the management
 * endpoints as the one thing in the process nobody checks.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Where the authenticated caller is left for the rest of the request.
     *
     * <p>A request attribute and not the security context: this is a gateway,
     * not an application, and it authenticates a request it is about to forward
     * rather than establishing a session it will use. The proxy filter reads it
     * from the same request, so there is no thread-local to leak across a
     * pooled worker.
     */
    public static final String CALLER_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".CALLER";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService tokens;
    private final RoleAuthorizer authorizer;
    private final ErrorResponseWriter errors;

    public JwtAuthenticationFilter(JwtService tokens, RoleAuthorizer authorizer, ErrorResponseWriter errors) {
        this.tokens = tokens;
        this.authorizer = authorizer;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RoleAuthorizer.Access access = authorizer.decide(request.getMethod(), request.getRequestURI());

        // A CORS preflight carries no credentials by design — the browser sends
        // it before it has, or will ever have, the token. Answering it 401 would
        // break every browser client in a way that looks like a CORS
        // misconfiguration rather than an auth failure, and OPTIONS performs no
        // action, so there is nothing to authorize.
        if (isPreflight(request)) {
            chain.doFilter(request, response);
            return;
        }

        if (access instanceof RoleAuthorizer.Access.Public) {
            chain.doFilter(request, response);
            return;
        }

        AuthenticatedCaller caller = authenticate(request);
        if (caller == null) {
            errors.write(request, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
            return;
        }

        String refusal = refuse(access, caller);
        if (refusal != null) {
            log.info("Refused {} {} to {}: {}", request.getMethod(), request.getRequestURI(), caller.username(), refusal);
            errors.write(request, response, HttpStatus.FORBIDDEN, refusal);
            return;
        }

        request.setAttribute(CALLER_ATTRIBUTE, caller);
        chain.doFilter(request, response);
    }

    /** @return the caller, or null when the request carried no usable token. */
    private AuthenticatedCaller authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            return tokens.parse(header.substring(BEARER_PREFIX.length()).trim());
        } catch (InvalidTokenException ex) {
            // Logged, not returned: the reason a token was refused is exactly
            // what an operator needs and exactly what a caller must not get. At
            // debug so a scanning client cannot flood the log at info.
            log.debug("Rejected a bearer token on {}: {}", request.getRequestURI(), ex.getMessage());
            return null;
        }
    }

    /**
     * @return the reason to answer 403, or null to let the request through
     */
    private String refuse(RoleAuthorizer.Access access, AuthenticatedCaller caller) {
        if (access instanceof RoleAuthorizer.Access.AnyOfRoles required && !caller.holdsAny(required.roles())) {
            List<String> names = required.roles().stream().map(Enum::name).sorted().toList();
            return "This action requires one of: " + String.join(", ", names);
        }
        return null;
    }

    /**
     * @return the caller left on the request, or empty when the request was not
     *     authenticated — the only correct way for a downstream header filter to
     *     tell "no roles" from "not authenticated", since a token with no roles
     *     and no token at all both mean an empty role list on the wire
     */
    public static Optional<AuthenticatedCaller> callerOn(HttpServletRequest request) {
        Object attribute = request.getAttribute(CALLER_ATTRIBUTE);
        return attribute instanceof AuthenticatedCaller caller ? Optional.of(caller) : Optional.empty();
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }
}

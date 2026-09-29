package com.raydans.apigateway.auth;

import com.raydans.apigateway.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The platform's authentication and authorization boundary (ADR 002): turns a
 * bearer header into an {@link AuthenticatedCaller} and decides whether the
 * caller may make this request.
 *
 * <p>A servlet filter rather than a gateway route filter because it has to run
 * for paths the proxy never sees — {@code /actuator/**} most of all, which is a
 * container route. A rule expressed as a route would leave the management
 * endpoints as the one thing in the process nobody checks.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Where the authenticated caller is left for the rest of the request. An
     * attribute rather than the security context: this is a gateway
     * authenticating a request it is about to forward, not establishing a
     * session, so there is no thread-local to leak across a pooled worker.
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
        RoleAuthorizer.Access access = authorizer.decide(request.getMethod(), pathOf(request));

        if (isPreflight(request) && authorizer.isPreflightExempt(pathOf(request))) {
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
            log.info("Refused {} {} to caller {}: {}",
                    request.getMethod(), pathOf(request), caller.callerId(), refusal);
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
            // Logged, not returned: the reason a token was refused is what an
            // operator needs and what a caller must not get. At debug so a
            // scanning client cannot flood the log at info.
            log.debug("Rejected a bearer token on {}: {}", pathOf(request), ex.getMessage());
            return null;
        }
    }

    /** @return the reason to answer 403, or null to let the request through. */
    private String refuse(RoleAuthorizer.Access access, AuthenticatedCaller caller) {
        if (access instanceof RoleAuthorizer.Access.AnyOfRoles required && !caller.holdsAny(required.roles())) {
            return "This action requires one of: " + String.join(", ", Role.namesOf(required.roles()));
        }
        return null;
    }

    /**
     * @return the caller left on the request, or empty when the request was not
     *     authenticated — the only way a downstream header filter can tell "no
     *     roles" from "not authenticated", since both mean an empty role list on
     *     the wire
     */
    public static Optional<AuthenticatedCaller> callerOn(HttpServletRequest request) {
        Object attribute = request.getAttribute(CALLER_ATTRIBUTE);
        return attribute instanceof AuthenticatedCaller caller ? Optional.of(caller) : Optional.empty();
    }

    /**
     * A CORS preflight is OPTIONS plus the two headers that make it one. The
     * browser sends it before it has a token and will never send one with it, so
     * a 401 here reads to a client as a broken CORS setup, and it performs no
     * action. Keyed on the whole definition rather than the method so a plain
     * OPTIONS to a management endpoint is still answered by this filter.
     *
     * <p>Whether that exemption applies is the author's question rather than this
     * filter's, because it is a question about the route: only the browser-facing
     * surface has a handshake to exempt ({@link RoleAuthorizer#isPreflightExempt}).
     * A preflight to {@code /actuator/**} is answered by the normal
     * authorization path instead, so the management endpoints are ADMIN's on
     * every method rather than on all of them but OPTIONS.
     */
    private static boolean isPreflight(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                && request.getHeader(HttpHeaders.ORIGIN) != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
    }

    /**
     * The path as the rules are written, without the context path. A rule that
     * missed because the deployment was given one would be an endpoint nobody
     * checks.
     */
    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}

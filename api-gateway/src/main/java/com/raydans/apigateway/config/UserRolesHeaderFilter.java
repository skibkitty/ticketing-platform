package com.raydans.apigateway.config;

import com.raydans.apigateway.auth.AuthenticatedCaller;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import com.raydans.apigateway.auth.Role;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Writes the caller's roles onto the request the gateway forwards downstream.
 * This is the trust boundary itself (ADR 002): everything past it rests on this
 * header being something the gateway decided rather than something a client
 * sent, so the value is always overwritten, never appended and never passed
 * through.
 */
@Component
public class UserRolesHeaderFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    /** The header the downstream services read the caller's roles from. */
    public static final String HEADER_NAME = "X-User-Roles";

    @Override
    public HttpHeaders apply(HttpHeaders inbound, ServerRequest request) {
        // Discarded before anything else is considered, and unconditionally.
        // `curl -H "X-User-Roles: ADMIN"` would otherwise be a complete bypass of
        // the gateway against services that, by ADR 002, have no authentication
        // of their own to notice — including for a request that reaches here
        // with no caller on it at all, the login surface among them.
        inbound.remove(HEADER_NAME);

        AuthenticatedCaller caller =
                JwtAuthenticationFilter.callerOn(request.servletRequest()).orElse(null);
        if (caller == null || caller.roles().isEmpty()) {
            return inbound;
        }

        // An empty X-User-Roles is a statement ("this caller has no roles") and a
        // missing one is the absence of one; only the first is ever true here.
        inbound.set(HEADER_NAME, String.join(",", Role.namesOf(caller.roles())));
        return inbound;
    }
}

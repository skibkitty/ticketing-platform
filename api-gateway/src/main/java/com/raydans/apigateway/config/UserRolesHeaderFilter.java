package com.raydans.apigateway.config;

import com.raydans.apigateway.auth.AuthenticatedCaller;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Writes the caller's roles onto the request the gateway forwards downstream.
 *
 * <p>This is the trust boundary itself (ADR 002). Everything past it — every
 * service, every authorization a service will ever make — rests on this header
 * being something the gateway decided rather than something a client sent. So
 * the value is always overwritten, never appended and never passed through.
 */
@Component
public class UserRolesHeaderFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    /** The header the downstream services read the caller's roles from. */
    public static final String HEADER_NAME = "X-User-Roles";

    /**
     * Applies the caller's roles to the outbound headers.
     *
     * <p>The inbound value is discarded unconditionally, before anything else
     * is considered. A client can send any header it likes, including this one,
     * so if what a downstream read were ever allowed to be the value that
     * arrived then {@code curl -H "X-User-Roles: ADMIN"} would be a complete
     * bypass of the gateway — and it would work against services that, by
     * ADR 002, have no authentication of their own to notice. Only a value
     * checked against a signature is allowed through.
     *
     * <p>Discarding first, rather than only overwriting when there is a caller
     * to overwrite it with, is the part that matters. A request that reaches
     * here with no caller on it — the login surface, or a path the gateway does
     * not serve — would otherwise keep whatever the client sent, and the header
     * would be trusted for precisely the requests nobody authenticated.
     *
     * <p>With no caller, or with a caller whose token grants no roles, the
     * header is left off rather than set to an empty value: an empty
     * {@code X-User-Roles} is a statement ("this caller has no roles") and a
     * missing one is the absence of a statement, and only the first of those is
     * ever true here.
     */
    @Override
    public HttpHeaders apply(HttpHeaders inbound, ServerRequest request) {
        inbound.remove(HEADER_NAME);

        AuthenticatedCaller caller =
                JwtAuthenticationFilter.callerOn(request.servletRequest()).orElse(null);
        if (caller == null || caller.roles().isEmpty()) {
            return inbound;
        }

        List<String> roles = caller.roles().stream().map(Enum::name).sorted().toList();
        inbound.set(HEADER_NAME, String.join(",", roles));
        return inbound;
    }
}

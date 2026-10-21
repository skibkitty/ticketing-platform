package com.raydans.apigateway.config;

import com.raydans.apigateway.auth.AuthenticatedCaller;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Writes the caller's {@code Customer.id} onto the request the gateway forwards
 * downstream, taken from the token subject the gateway already verified
 * (ADR 002).
 *
 * <p>This is the other half of the trust boundary, and the part with more to
 * lose: {@code X-Customer-Id} is what {@code POST /api/v1/reservations} books
 * seats against, so a downstream service that trusted a client-supplied value
 * would be handing out reservations in someone else's name. The value is always
 * overwritten from the verified subject, never appended and never passed through.
 *
 * <p>Only a caller that <em>is</em> a Customer gets the header at all. An
 * organizer and an admin authenticate like anyone else and have an id of their
 * own, but holding those roles is not being a Customer — the domain gives them
 * no Customer identity (CONTEXT.md) — so there is no Customer id to publish and
 * the header is left off rather than filled with a number that means something
 * else. {@code ReservationController} requires the header, so an organizer's
 * request there is refused at the service, which is the same answer the
 * authorization table gives it one hop earlier.
 *
 * <p>Deliberately not a lookup of the caller by anything the client sent, and
 * not a table of usernames: the identity is already in the token, and a
 * signature the gateway checked is the only thing that can vouch for it.
 */
@Component
public class CustomerIdHeaderFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    /**
     * The header the downstream services read the caller's {@code Customer.id}
     * from. The name {@code ReservationController} already requires it under.
     */
    public static final String HEADER_NAME = "X-Customer-Id";

    @Override
    public HttpHeaders apply(HttpHeaders inbound, ServerRequest request) {
        // Discarded before anything else is considered, and unconditionally.
        // `curl -H "X-Customer-Id: 999"` would otherwise reserve seats as a
        // Customer nobody authenticated, and the service downstream has no
        // authentication of its own to notice (ADR 002) — including on a request
        // that reaches here with no caller on it at all, and including one made by
        // a caller who is not a Customer and so will not be given a replacement.
        // This is why the removal is not inside either branch below.
        inbound.remove(HEADER_NAME);

        AuthenticatedCaller caller =
                JwtAuthenticationFilter.callerOn(request.servletRequest()).orElse(null);
        if (caller == null) {
            return inbound;
        }

        // The single question of whether the subject may be published as a
        // Customer id, asked of the caller rather than re-derived here.
        if (!caller.isCustomer()) {
            return inbound;
        }

        // The subject was read as a caller's id or the request would not have
        // been authenticated at all, so this cannot fail here: the header is
        // either a number the gateway verified or it is absent.
        inbound.set(HEADER_NAME, Long.toString(caller.callerId()));
        return inbound;
    }
}

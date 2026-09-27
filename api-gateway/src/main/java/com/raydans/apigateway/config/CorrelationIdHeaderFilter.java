package com.raydans.apigateway.config;

import com.raydans.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Puts the correlation id on the request the gateway forwards.
 *
 * <p>{@link CorrelationIdFilter} decides the id — echoing a caller's or minting
 * one — and exposes it two ways: on the response, for the caller, and in the
 * logging context, for this process. Neither of those reaches the services
 * behind the gateway, because the id the filter generated only ever existed in
 * the gateway's own head.
 *
 * <p>That is why this is here and not in the shared filter. A service whose
 * caller sent an id already has it on the request it was handed, and a service
 * that generates one is generating it for its own logs only. The gateway is the
 * one place where a generated id has to reach a process that has never seen the
 * request, and where an id the caller supplied has to survive the hop. Written
 * here it is also written as what it is: the gateway's own outbound header,
 * overwritten from this request's decided value so that a caller's header and the
 * gateway's logs can never tell different stories about the same request.
 */
@Component
public class CorrelationIdHeaderFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    @Override
    public HttpHeaders apply(HttpHeaders inbound, ServerRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            inbound.set(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        return inbound;
    }
}

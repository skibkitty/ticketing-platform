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
 * <p>{@link CorrelationIdFilter} decides the id and exposes it on the response
 * and in the logging context; neither reaches the services behind the gateway,
 * because a generated id only ever existed in the gateway's head. This is
 * gateway-local for that reason — a service that generates an id is generating it
 * for its own logs, and a service whose caller sent one already has it on the
 * request it was handed.
 *
 * <p>Overwritten from the decided value, so a caller's header and the gateway's
 * logs can never tell different stories about the same request.
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

package com.raydans.apigateway.config;

import com.raydans.common.web.CorrelationIdFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Takes the service's correlation id off the response the gateway hands back.
 *
 * <p>The service answers with the id it was handed by {@link CorrelationIdHeaderFilter},
 * and the gateway's own response already carries it from {@link CorrelationIdFilter},
 * so copying the service's header too hands the caller the same id twice.
 */
@Component
public class CorrelationIdResponseHeaderFilter implements HttpHeadersFilter.ResponseHttpHeadersFilter {

    @Override
    public HttpHeaders apply(HttpHeaders outbound, ServerResponse response) {
        outbound.remove(CorrelationIdFilter.HEADER_NAME);
        return outbound;
    }
}

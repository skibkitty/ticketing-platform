package com.raydans.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.RoleAuthorizer;
import com.raydans.apigateway.web.ErrorResponseWriter;
import com.raydans.common.web.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.gateway.server.mvc.predicate.MvcPredicateSupplier;
import org.springframework.cloud.gateway.server.mvc.predicate.PredicateSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the gateway's filter chain, and the order of it.
 *
 * <p>Two filters, and the order between them is the only thing that makes a 401
 * traceable: the correlation id has to be on the request before auth can refuse
 * it, or a rejected call is the one request in the system with no id to grep its
 * logs by. That is the opposite of the usual instinct to put the cheap filter
 * first.
 */
@Configuration
public class GatewayWebConfig {

    /**
     * Correlation id, ahead of everything.
     *
     * <p>Registered here rather than picked up from {@code common}'s
     * auto-configuration because that is a {@code WebMvcConfigurer} for CORS and
     * a filter has to be registered explicitly — the same way every other
     * service in this repo does it.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public ErrorResponseWriter errorResponseWriter(ObjectMapper objectMapper) {
        return new ErrorResponseWriter(objectMapper);
    }

    /**
     * Teaches the gateway which predicate names {@code application.yml} may use.
     *
     * <p>The {@code Path=/api/v1/events/**} lines under
     * {@code spring.cloud.gateway.mvc.routes} are not built-in: Gateway discovers
     * them by reflecting over the factory methods a {@code PredicateSupplier}
     * bean points at, and without this bean it finds no operations, leaves every
     * route's predicate null, and the context dies on startup with the unhelpful
     * {@code "Predicate must not be null"}.
     */
    @Bean
    public PredicateSupplier mvcPredicateSupplier() {
        return new MvcPredicateSupplier();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(
            JwtService tokens, ErrorResponseWriter errors) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokens, new RoleAuthorizer(), errors));
        // Immediately after the correlation id, and before anything that could
        // route: an unauthenticated request should not reach a route handler,
        // an actuator endpoint, or an error page that renders it.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}

package com.raydans.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtAuthenticationFilter;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.RoleAuthorizer;
import com.raydans.apigateway.resilience.GatewayResilienceFilter;
import com.raydans.apigateway.web.AccessLogFilter;
import com.raydans.apigateway.web.ErrorResponseWriter;
import com.raydans.common.web.CorrelationIdFilter;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.gateway.server.mvc.predicate.MvcPredicateSupplier;
import org.springframework.cloud.gateway.server.mvc.predicate.PredicateSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the gateway's filter chain, and the order of it.
 *
 * <p>The correlation id is ahead of everything, and that is the opposite of the
 * usual instinct to put the cheap filter first: the id has to be on the request
 * before auth can refuse it, or a rejected call is the one request in the
 * system with no id to grep its logs by.
 */
@Configuration
public class GatewayWebConfig {

    /**
     * The correlation id filter, registered rather than picked up from
     * {@code common}'s auto-configuration because a filter has to be registered
     * explicitly.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Inside the correlation id, so the line carries it, and outside everything
     * else, so a request auth or resilience turns away is logged with the status
     * it was turned away with.
     */
    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    /**
     * The authorization table, as a bean rather than a {@code new} inside the
     * filter registration below.
 *
     * <p>So that there is one instance of it and something else can ask it
     * questions: {@code GatewayProxyBootTests} autowires this to walk the real
     * route table and check every served path has a row, which is the wiring claim
     * no test of the table in isolation can make. A table constructed inline would
     * be a second copy as far as anything asking is concerned — and the point of
     * the check is to ask the same one the filter enforces.
     */
    @Bean
    public RoleAuthorizer roleAuthorizer() {
        return new RoleAuthorizer();
    }

    @Bean
    public ErrorResponseWriter errorResponseWriter(ObjectMapper objectMapper) {
        return new ErrorResponseWriter(objectMapper);
    }

    /**
     * Immediately after the correlation id, and before anything that could route:
     * an unauthenticated request should not reach a route handler, an actuator
     * endpoint, or an error page that renders it.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(
            JwtService tokens, RoleAuthorizer authorizer, ErrorResponseWriter errors) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokens, authorizer, errors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * After authentication, so that only a request the gateway would otherwise
     * forward can spend the flash-sale budget or count against payment-service's
     * circuit: an unauthenticated flood is refused by the filter above, cheaply,
     * and leaves both to real callers (#13).
     */
    @Bean
    public FilterRegistrationBean<GatewayResilienceFilter> gatewayResilienceFilter(
            RateLimiterRegistry rateLimiters,
            BulkheadRegistry bulkheads,
            CircuitBreakerRegistry circuitBreakers,
            ErrorResponseWriter errors) {
        FilterRegistrationBean<GatewayResilienceFilter> registration = new FilterRegistrationBean<>(
                new GatewayResilienceFilter(
                        rateLimiters.rateLimiter("reservations"),
                        bulkheads.bulkhead("reservations"),
                        circuitBreakers.circuitBreaker("payments"),
                        errors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
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
}

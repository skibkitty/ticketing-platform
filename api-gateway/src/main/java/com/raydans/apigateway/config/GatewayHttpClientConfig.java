package com.raydans.apigateway.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The client the gateway proxies through, Apache HttpClient (#44), with a
 * connection pool sized for the gateway rather than for an ordinary application.
 *
 * <p>Apache's default is 5 connections per service. Every hop to a service
 * needs a connection for as long as it is in flight, so past 5 the next hop waits
 * inside the gateway, holding its thread, for one to come free. The reservation
 * bulkhead admits 20 (#13); with the default pool, 15 of those would queue here,
 * which is the pile-up the bulkhead exists to prevent. The per-service size is
 * therefore a deployment setting, and has to be at least the bulkhead's
 * (the gateway refuses to start otherwise).
 *
 * <p>The pool is one per gateway, with a cap per service (Apache's "route", one
 * per host and port), so a service holding every connection it may have leaves
 * the others theirs.
 */
@Configuration
public class GatewayHttpClientConfig {

    /** The three services, plus headroom, so the total never caps a service below its own limit. */
    private static final int SERVICES_SHARING_THE_POOL = 4;

    /**
     * The pool, and how long a hop may wait for a connection from it.
     *
     * <p>Apache waits three minutes by default, and Boot does not change that:
     * with a service holding every connection, the next request to it would sit
     * in the gateway until a held one timed out, which is the hang the read
     * timeout was added to prevent. The wait is bounded by the connect timeout,
     * because both are the same question from the caller's side — how long to
     * spend getting a connection before giving up — and a separate setting for it
     * would be one more number to keep consistent with that one.
     */
    @Bean
    public ClientHttpRequestFactoryBuilder<?> gatewayClientHttpRequestFactoryBuilder(
            @Value("${app.gateway.http-client.max-connections-per-service}") int perService,
            @Value("${spring.http.client.connect-timeout}") Duration connectTimeout) {
        HttpComponentsClientHttpRequestFactoryBuilder builder = ClientHttpRequestFactoryBuilder.httpComponents();
        return builder
                .withConnectionManagerCustomizer(pool -> pool
                        .setMaxConnPerRoute(perService)
                        .setMaxConnTotal(perService * SERVICES_SHARING_THE_POOL))
                .withDefaultRequestConfigCustomizer(request -> request
                        .setConnectionRequestTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS));
    }
}

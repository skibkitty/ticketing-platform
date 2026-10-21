package com.raydans.apigateway.config;

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
 * therefore a deployment setting, and has to be at least the bulkhead's.
 */
@Configuration
public class GatewayHttpClientConfig {

    /** The three services, plus headroom, so one service cannot use up the whole pool. */
    private static final int SERVICES_SHARING_THE_POOL = 4;

    @Bean
    public ClientHttpRequestFactoryBuilder<?> gatewayClientHttpRequestFactoryBuilder(
            @Value("${app.gateway.http-client.max-connections-per-service}") int perService) {
        HttpComponentsClientHttpRequestFactoryBuilder builder = ClientHttpRequestFactoryBuilder.httpComponents();
        return builder.withConnectionManagerCustomizer(pool -> pool
                .setMaxConnPerRoute(perService)
                .setMaxConnTotal(perService * SERVICES_SHARING_THE_POOL));
    }
}

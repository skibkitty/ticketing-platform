package com.raydans.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The platform's only public surface (ADR 001, ADR 002). Everything a caller can
 * reach goes through this process: it authenticates the request, authorizes it,
 * and forwards it to one of the internal services with the caller's roles
 * attached as a header the downstreams trust.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

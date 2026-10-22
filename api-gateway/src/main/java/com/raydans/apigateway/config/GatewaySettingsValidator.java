package com.raydans.apigateway.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a gateway whose resilience settings are individually valid but
 * do not mean what they say (#13).
 *
 * <p>resilience4j and Spring already refuse a non-positive limit, an out-of-range
 * failure rate and an unparseable duration at startup. These are the settings
 * that start without complaint and then behave differently from what was
 * configured, so each message names the environment variable to change:
 *
 * <ul>
 *   <li>a zero timeout, which Apache HttpClient reads as "wait forever";
 *   <li>a connection pool too small for the bulkhead, so admitted reservations
 *       queue inside the gateway for a connection;
 *   <li>a circuit minimum larger than its window, which resilience4j quietly
 *       caps at the window size.
 * </ul>
 */
@Component
public class GatewaySettingsValidator {

    public GatewaySettingsValidator(
            @Value("${app.gateway.http-client.max-connections-per-service}") int connectionsPerService,
            @Value("${spring.http.client.connect-timeout}") Duration connectTimeout,
            @Value("${spring.http.client.read-timeout}") Duration readTimeout,
            @Value("${resilience4j.circuitbreaker.instances.payments.minimum-number-of-calls}") int paymentsMinCalls,
            BulkheadRegistry bulkheads,
            CircuitBreakerRegistry circuitBreakers) {
        List<String> problems = new ArrayList<>();

        if (!connectTimeout.isPositive()) {
            problems.add("GATEWAY_CONNECT_TIMEOUT must be positive (was " + connectTimeout
                    + "); zero means a hop waits for a connection forever");
        }
        if (!readTimeout.isPositive()) {
            problems.add("GATEWAY_READ_TIMEOUT must be positive (was " + readTimeout
                    + "); zero means a hop waits for a service's answer forever");
        }

        int bulkhead = bulkheads.bulkhead("reservations").getBulkheadConfig().getMaxConcurrentCalls();
        if (connectionsPerService < bulkhead) {
            problems.add("GATEWAY_MAX_CONNECTIONS_PER_SERVICE (" + connectionsPerService
                    + ") must be at least RESERVATION_BULKHEAD_MAX_CONCURRENT (" + bulkhead
                    + "), or reservations the bulkhead admits queue inside the gateway for a connection");
        }

        // Read from the property rather than the built config, because the built
        // config has already been capped to the window and would always agree.
        CircuitBreakerConfig payments = circuitBreakers.circuitBreaker("payments").getCircuitBreakerConfig();
        if (paymentsMinCalls > payments.getSlidingWindowSize()) {
            problems.add("PAYMENTS_CIRCUIT_MIN_CALLS (" + paymentsMinCalls
                    + ") must not exceed PAYMENTS_CIRCUIT_WINDOW_SIZE (" + payments.getSlidingWindowSize()
                    + "); the circuit would quietly use the window size instead");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Gateway resilience settings are inconsistent:\n  - " + String.join("\n  - ", problems));
        }
    }
}

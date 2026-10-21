package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The gateway's resilience settings as a deployment states them: environment
 * variables, read at startup (#13).
 *
 * <p>Each test starts the real application with the variables in a
 * {@code systemEnvironment} property source, which is where Spring puts the
 * process environment, so a variable is resolved exactly as a container's would
 * be. Overriding a Spring property path instead would test the binding and not
 * the names a deployment actually uses.
 */
class GatewaySettingsStartupTests {

    /** What every gateway needs regardless of the settings under test. */
    private static final Map<String, Object> REQUIRED = Map.of(
            "JWT_SECRET", "a-signing-key-this-test-only-ever-signs-with",
            "DEMO_CUSTOMER_PASSWORD", "the-customer-password",
            "DEMO_ORGANIZER_PASSWORD", "the-organizer-password",
            "DEMO_ADMIN_PASSWORD", "the-admin-password");

    @Test
    void everyResilienceVariableChangesTheSettingTheGatewayRunsWith() {
        Map<String, Object> environment = new HashMap<>();
        environment.put("RESERVATION_RATE_LIMIT", "7");
        environment.put("RESERVATION_RATE_LIMIT_PERIOD", "PT30S");
        environment.put("RESERVATION_BULKHEAD_MAX_CONCURRENT", "6");
        environment.put("PAYMENTS_CIRCUIT_WINDOW_SIZE", "12");
        environment.put("PAYMENTS_CIRCUIT_MIN_CALLS", "4");
        environment.put("PAYMENTS_CIRCUIT_FAILURE_RATE", "25");
        environment.put("PAYMENTS_CIRCUIT_OPEN_FOR", "PT45S");
        environment.put("PAYMENTS_CIRCUIT_TRIAL_CALLS", "2");
        environment.put("GATEWAY_CONNECT_TIMEOUT", "PT3S");
        environment.put("GATEWAY_READ_TIMEOUT", "PT7S");
        environment.put("GATEWAY_MAX_CONNECTIONS_PER_SERVICE", "9");

        try (ConfigurableApplicationContext gateway = start(environment)) {
            RateLimiterConfig rate = gateway.getBean(RateLimiterRegistry.class)
                    .rateLimiter("reservations").getRateLimiterConfig();
            assertThat(rate.getLimitForPeriod()).isEqualTo(7);
            assertThat(rate.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(30));
            assertThat(rate.getTimeoutDuration()).isZero();

            assertThat(gateway.getBean(BulkheadRegistry.class).bulkhead("reservations")
                    .getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(6);

            CircuitBreakerConfig circuit = gateway.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("payments").getCircuitBreakerConfig();
            assertThat(circuit.getSlidingWindowSize()).isEqualTo(12);
            assertThat(circuit.getMinimumNumberOfCalls()).isEqualTo(4);
            assertThat(circuit.getFailureRateThreshold()).isEqualTo(25f);
            assertThat(circuit.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(45_000L);
            assertThat(circuit.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);

            ClientHttpRequestFactorySettings client = gateway.getBean(ClientHttpRequestFactorySettings.class);
            assertThat(client.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(client.readTimeout()).isEqualTo(Duration.ofSeconds(7));

            assertThat(gateway.getEnvironment().getProperty(
                    "app.gateway.http-client.max-connections-per-service", Integer.class)).isEqualTo(9);
        }
    }

    @Test
    void theShippedDefaultsAreTheDocumentedOnes() {
        try (ConfigurableApplicationContext gateway = start(Map.of())) {
            RateLimiterConfig rate = gateway.getBean(RateLimiterRegistry.class)
                    .rateLimiter("reservations").getRateLimiterConfig();
            assertThat(rate.getLimitForPeriod()).isEqualTo(100);
            assertThat(rate.getLimitRefreshPeriod()).isEqualTo(Duration.ofMinutes(1));
            assertThat(gateway.getBean(BulkheadRegistry.class).bulkhead("reservations")
                    .getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);

            CircuitBreakerConfig circuit = gateway.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("payments").getCircuitBreakerConfig();
            assertThat(circuit.getSlidingWindowSize()).isEqualTo(10);
            assertThat(circuit.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(circuit.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(circuit.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
            assertThat(circuit.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);

            ClientHttpRequestFactorySettings client = gateway.getBean(ClientHttpRequestFactorySettings.class);
            assertThat(client.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(client.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(gateway.getEnvironment().getProperty(
                    "app.gateway.http-client.max-connections-per-service", Integer.class)).isEqualTo(50);
        }
    }

    /**
     * Settings the gateway must refuse to start with, and the variable the
     * refusal names. The first group start silently without the gateway's own
     * check; the second are refused by resilience4j or Spring, and are here so a
     * dependency upgrade that stopped refusing them is noticed.
     */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "GATEWAY_CONNECT_TIMEOUT, PT0S, GATEWAY_CONNECT_TIMEOUT",
        "GATEWAY_READ_TIMEOUT, PT0S, GATEWAY_READ_TIMEOUT",
        "GATEWAY_MAX_CONNECTIONS_PER_SERVICE, 0, GATEWAY_MAX_CONNECTIONS_PER_SERVICE",
        "GATEWAY_MAX_CONNECTIONS_PER_SERVICE, 19, RESERVATION_BULKHEAD_MAX_CONCURRENT",
        "PAYMENTS_CIRCUIT_MIN_CALLS, 11, PAYMENTS_CIRCUIT_WINDOW_SIZE",
        "RESERVATION_RATE_LIMIT, 0, limitForPeriod",
        "RESERVATION_RATE_LIMIT_PERIOD, PT0S, LimitRefreshPeriod",
        "RESERVATION_BULKHEAD_MAX_CONCURRENT, 0, maxConcurrentCalls",
        "PAYMENTS_CIRCUIT_FAILURE_RATE, 101, failureRateThreshold",
        "PAYMENTS_CIRCUIT_FAILURE_RATE, 0, failureRateThreshold",
        "PAYMENTS_CIRCUIT_OPEN_FOR, PT0S, waitDurationInOpenState",
        "PAYMENTS_CIRCUIT_TRIAL_CALLS, 0, permittedNumberOfCallsInHalfOpenState",
        "PAYMENTS_CIRCUIT_WINDOW_SIZE, 0, slidingWindowSize",
        "GATEWAY_READ_TIMEOUT, banana, banana"
    })
    void anUnusableSettingStopsTheGatewayFromStarting(String variable, String value, String named) {
        assertThatThrownBy(() -> start(Map.of(variable, value)).close())
                .satisfies(failure -> assertThat(rootCauseOf(failure).getMessage()).contains(named));
    }

    private static ConfigurableApplicationContext start(Map<String, Object> variables) {
        Map<String, Object> all = new HashMap<>(REQUIRED);
        all.putAll(variables);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, all));
        return new SpringApplicationBuilder(ApiGatewayApplication.class)
                .environment(environment)
                .properties("server.port=0")
                .run();
    }

    private static Throwable rootCauseOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}

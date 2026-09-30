package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationServiceBootTests {

    static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No Kafka broker is mounted here; the PaymentOutcomeConsumer listener container
        // must not try to start.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"UP\"");
    }

    @Test
    void echoesCorrelationIdWhenProvided() {
        // In the form the platform propagates — a canonical UUID. A caller
        // supplying anything else is answered with a generated id rather than
        // refused, because the id is a debugging affordance and refusing the
        // request over it would turn a diagnostic into an outage.
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRELATION_HEADER, "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rest.exchange("/actuator/health", HttpMethod.GET, entity, String.class);

        assertThat(response.getHeaders().getFirst(CORRELATION_HEADER))
                .isEqualTo("3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b");
    }

    @Test
    void replacesACorrelationIdItWillNotPropagate() {
        // The service-side half of the same rule, and the reason it is asserted
        // here as well as in the filter's own test: a host-local caller skipping
        // the gateway can send this header directly, so the rule cannot be a
        // gateway-only one. Without it, this value would be in the MDC of every
        // log line this service writes about the request.
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRELATION_HEADER, "an-id-of-my-own\nINFO somebody did something");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rest.exchange("/actuator/health", HttpMethod.GET, entity, String.class);

        String returned = response.getHeaders().getFirst(CORRELATION_HEADER);
        assertThat(returned).doesNotContain("\n");
        assertThat(UUID.fromString(returned).toString()).isEqualTo(returned);
    }

    @Test
    void generatesCorrelationIdWhenAbsent() {
        String first = rest.getForEntity("/actuator/health", String.class)
                .getHeaders().getFirst(CORRELATION_HEADER);
        String second = rest.getForEntity("/actuator/health", String.class)
                .getHeaders().getFirst(CORRELATION_HEADER);

        assertThat(first).isNotBlank();
        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void flywayAppliesReservationSchemaTables() {
        Integer tableCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'reservation'",
                Integer.class);

        assertThat(tableCount).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation.flyway_schema_history", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }
}
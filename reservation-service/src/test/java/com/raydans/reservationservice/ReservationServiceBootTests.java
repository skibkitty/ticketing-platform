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
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRELATION_HEADER, "incoming-id");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rest.exchange("/actuator/health", HttpMethod.GET, entity, String.class);

        assertThat(response.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("incoming-id");
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
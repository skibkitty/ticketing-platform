package com.raydans.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V4 against a database that already holds payments, which is the case it has to
 * survive: the compose stack keeps Postgres on a named volume, so an existing
 * deployment arrives at V4 with rows that were written before ownership existed.
 *
 * <p>Flyway is driven directly rather than through a Spring context so the database
 * can be stopped at V3, populated the way the pre-V4 service populated it, and only
 * then moved forward.
 */
@Testcontainers
class PaymentOwnershipMigrationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Test
    void existingPaymentsSurviveV4UnchangedAndWithNoOwner() {
        JdbcTemplate jdbc = jdbc();
        flywayUpTo("3").migrate();
        // The pre-V4 claim's exact shape: no customer_id column to write.
        jdbc.update("INSERT INTO payment.payments (reservation_id, amount_cents, status, settled_at) "
                + "VALUES (1, 44000, 'SUCCEEDED', now()), (2, 50000, 'FAILED', now())");

        flywayUpTo("latest").migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reservation_id, amount_cents, status, customer_id FROM payment.payments ORDER BY reservation_id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("reservation_id", 1L).containsEntry("amount_cents", 44000)
                .containsEntry("status", "SUCCEEDED");
        assertThat(rows.get(1)).containsEntry("reservation_id", 2L).containsEntry("amount_cents", 50000)
                .containsEntry("status", "FAILED");
        // Not backfilled: no trusted owner exists in this schema (ADR 005, ADR 016), and
        // a guessed one would be worse than none. A null owner matches no caller.
        assertThat(rows).allSatisfy(row -> assertThat(row.get("customer_id")).isNull());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.payments WHERE reservation_id = 1 AND customer_id = 99",
                        Integer.class))
                .isZero();

        // The post-V4 claim still works alongside them, and the uniqueness that makes
        // it idempotent was not disturbed by the migration.
        jdbc.update("INSERT INTO payment.payments (reservation_id, customer_id, amount_cents, status, settled_at) "
                + "VALUES (3, 99, 1000, 'SUCCEEDED', now())");
        int duplicate = jdbc.update(
                "INSERT INTO payment.payments (reservation_id, customer_id, amount_cents, status, settled_at) "
                        + "VALUES (1, 99, 1000, 'SUCCEEDED', now()) ON CONFLICT (reservation_id) DO NOTHING");
        assertThat(duplicate).as("a legacy row cannot be re-claimed with an owner").isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT customer_id FROM payment.payments WHERE reservation_id = 3", Long.class))
                .isEqualTo(99L);
    }

    private Flyway flywayUpTo(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("payment")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}

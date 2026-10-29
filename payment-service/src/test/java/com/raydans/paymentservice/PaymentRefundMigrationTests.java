package com.raydans.paymentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V5 ({@code refunded_at}, #52) against a database that already holds payments, and the
 * other direction a deployment has to survive: the previous build, which knows only
 * V1-V4, starting against a database this build has already moved to V5 (a rollback).
 *
 * <p>Flyway is driven directly, as in {@link PaymentOwnershipMigrationTests}, so the
 * database can be stopped at V4 and populated the way the pre-#52 service populated it.
 */
@Testcontainers
class PaymentRefundMigrationTests {

    static final List<String> PRE_REFUND_MIGRATIONS = List.of(
            "V1__init.sql", "V2__outbox_event_id.sql", "V3__outbox_event_id_constraints.sql",
            "V4__payment_customer_id.sql");

    // Per test, not static: each test needs to choose the version its database starts at.
    @Container
    final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Test
    void existingPaymentsSurviveV5UnchangedNotRefundedAndStillRefundable() {
        JdbcTemplate jdbc = jdbc();
        flyway("classpath:db/migration", "4").migrate();
        // Every shape a pre-#52 database holds: owned and ownerless (pre-V4), settled both ways.
        jdbc.update("INSERT INTO payment.payments (reservation_id, customer_id, amount_cents, status, settled_at) "
                + "VALUES (1, 99, 44000, 'SUCCEEDED', now()), (2, 99, 50000, 'FAILED', now()), "
                + "(3, NULL, 1000, 'SUCCEEDED', now())");

        MigrateResult result = flyway("classpath:db/migration", "latest").migrate();

        assertThat(result.targetSchemaVersion).isEqualTo("5");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reservation_id, customer_id, amount_cents, status, refunded_at "
                        + "FROM payment.payments ORDER BY reservation_id");
        assertThat(rows).extracting(row -> row.get("status")).containsExactly("SUCCEEDED", "FAILED", "SUCCEEDED");
        assertThat(rows).extracting(row -> row.get("amount_cents")).containsExactly(44000, 50000, 1000);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("refunded_at"))
                .as("no existing Payment is born refunded")
                .isNull());

        // The refund's own statement on the legacy rows: a SUCCEEDED one (ownerless too)
        // refunds once, a FAILED one never does.
        String refund = "UPDATE payment.payments SET status = 'REFUNDED', refunded_at = now() "
                + "WHERE reservation_id = ? AND status = 'SUCCEEDED'";
        assertThat(jdbc.update(refund, 1L)).isEqualTo(1);
        assertThat(jdbc.update(refund, 1L)).as("the status guard makes a repeat a no-op").isZero();
        assertThat(jdbc.update(refund, 2L)).as("a declined Payment has nothing to refund").isZero();
        assertThat(jdbc.update(refund, 3L)).as("an ownerless Payment is refunded like any other").isEqualTo(1);
    }

    @Test
    void thePreviousBuildStillValidatesAndStartsAgainstADatabaseAlreadyAtV5(@TempDir Path previousBuild)
            throws IOException {
        flyway("classpath:db/migration", "latest").migrate();
        // What the pre-#52 jar has on its classpath: every migration but V5.
        for (String migration : PRE_REFUND_MIGRATIONS) {
            try (InputStream in = getClass().getResourceAsStream("/db/migration/" + migration)) {
                Files.copy(in, previousBuild.resolve(migration));
            }
        }

        Flyway previous = flyway("filesystem:" + previousBuild, "latest");
        ValidateResult validation = previous.validateWithResult();
        MigrateResult migration = previous.migrate();

        // Flyway's default ignoreMigrationPatterns (*:future) treats V5 as a future
        // migration, so a rolled-back build boots instead of failing validation. The
        // column stays; the previous code never reads it.
        assertThat(validation.validationSuccessful)
                .as("validation errors: %s", validation.invalidMigrations)
                .isTrue();
        assertThat(migration.migrationsExecuted).isZero();
    }

    private Flyway flyway(String location, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("payment")
                .createSchemas(true)
                .locations(location)
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}

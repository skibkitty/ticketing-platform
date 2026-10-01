package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxPublisher;
import com.raydans.reservationservice.reservation.HoldExpirer;
import com.raydans.reservationservice.reservation.ReservationService;
import com.raydans.reservationservice.web.ReservationController;
import com.raydans.reservationservice.web.SeatStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationFlowBootTests {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String CUSTOMER_HEADER = ReservationController.CUSTOMER_HEADER;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxPublisher outboxPublisher;

    @Autowired
    HoldExpirer holdExpirer;

    @Autowired
    ReservationService reservations;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.outbox.poll-interval-ms", () -> "60000");
        registry.add("app.hold.expire-interval-ms", () -> "60000");
    }

    @Test
    void reservationCreatedReachesKafkaHoldsSeatsAndIsReadable() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000),
                Map.of("section", "Orchestra", "row", "B", "seatNumber", 2, "priceCents", 12000)));

        ResponseEntity<Map> reservation = postReservation(created.eventId(),
                List.of(created.seatIds().get(0), created.seatIds().get(1)), 99L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number reservationId = (Number) reservation.getBody().get("id");
        assertThat(reservation.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(reservation.getBody()).containsEntry("amountCents", 27000);
        String correlationId = reservation.getHeaders().getFirst(CORRELATION_HEADER);
        assertThat(correlationId).isNotBlank();

        outboxPublisher.poll();

        ConsumerRecord<String, String> record = awaitMessage(reservationId.longValue());
        assertThat(record.key()).isEqualTo(new UUID(0L, reservationId.longValue()).toString());
        assertThat(record.headers().lastHeader(CORRELATION_HEADER)).isNotNull();
        assertThat(new String(record.headers().lastHeader(CORRELATION_HEADER).value()))
                .isEqualTo(correlationId);

        assertThat(record.value())
                .contains("\"eventType\":\"reservation.ReservationCreated\"")
                .contains("\"correlationId\":\"" + correlationId + "\"")
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":99")
                .contains("\"amountCents\":27000");

        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.seats WHERE status = 'HELD'", Integer.class);
        assertThat(held).isEqualTo(2);
        awaitOutboxPublished(1);

        ResponseEntity<Map> byId = getReservation(reservationId.longValue(), 99L);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(byId.getBody()).containsEntry("amountCents", 27000);

        // Scoped to this test's Customer (99), who holds exactly one reservation
        // among the ones this test created — the id in the path still chooses
        // which, and the header still says whose (ADR 014).
        ResponseEntity<List> byCustomer = listReservations(99L, "?customerId=99");
        assertThat(byCustomer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byCustomer.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map> mineOnly = (List<Map>) byCustomer.getBody();
        assertThat(mineOnly).extracting(row -> row.get("customerId")).containsOnly(99);
    }

    @Test
    void secondReservationForSameSeatReturns409AndFirstHoldIsUntouched() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "A", "seatNumber", 1, "priceCents", 5000)));

        ResponseEntity<Map> first =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 7L);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 8L);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("status", 409);

        Integer heldForEvent = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.seats WHERE event_id = ? AND status = 'HELD'",
                Integer.class, created.eventId());
        assertThat(heldForEvent).isEqualTo(1);
        Integer reservationsForLoser = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.reservations WHERE customer_id = 8", Integer.class);
        assertThat(reservationsForLoser).isZero();
        long firstReservationId = ((Number) first.getBody().get("id")).longValue();
        Integer seatLinks = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.reservation_seats WHERE reservation_id = ?",
                Integer.class, firstReservationId);
        assertThat(seatLinks).isEqualTo(1);
    }

    @Test
    void getReservationForUnknownIdReturns404() {
        ResponseEntity<Map> response = getReservation(422L, 99L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    // --- a Customer's reservations are read by identity (ADR 014) ----------------
    //
    // The complete path from the gateway's header onward: a real controller, a real
    // service and a real database, with the customer id arriving exactly as the
    // gateway derives it from a verified token (ADR 002). The gateway's own half —
    // that it derives and unforgeably rewrites that header — is asserted in
    // GatewayProxyBootTests, and Customer A cannot read Customer B's data at either
    // seam.

    @Test
    void aCustomerCanReadTheirOwnReservationById() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "A", "seatNumber", 1, "priceCents", 15000)));
        ResponseEntity<Map> created_ = postReservation(created.eventId(), created.seatIds(), 501L);
        long reservationId = ((Number) created_.getBody().get("id")).longValue();

        ResponseEntity<Map> mine = getReservation(reservationId, 501L);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).containsEntry("id", (int) reservationId);
        assertThat(mine.getBody()).containsEntry("customerId", 501);
    }

    @Test
    void aCustomerCannotReadAnotherCustomersReservationById() {
        // The bypass this closes, end to end. Customer 7 holds a valid
        // X-Customer-Id and knows 99's reservation id; the answer is the same 404
        // as for an id nobody was ever issued, so the response does not even
        // confirm the reservation exists.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Balcony", "row", "B", "seatNumber", 2, "priceCents", 9000)));
        ResponseEntity<Map> owned = postReservation(created.eventId(), created.seatIds(), 502L);
        long reservationId = ((Number) owned.getBody().get("id")).longValue();

        ResponseEntity<Map> theirs = getReservation(reservationId, 503L);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.getBody()).containsEntry("status", 404);
        // The 404 is an error body, not a reservation, and it carries none of the
        // reservation's fields — so a caller learns neither that 42 exists nor what
        // it holds. ("status" is the shared ApiErrorResponse's own HTTP status, not
        // the reservation's lifecycle state.)
        assertThat(theirs.getBody())
                .containsEntry("error", "Not Found")
                .doesNotContainKeys("id", "customerId", "eventId", "amountCents", "seats", "expiresAt");
    }

    @Test
    void readingAnotherCustomersReservationDoesNotDisturbIt() {
        // A refused read must leave the owner's row exactly as it was. This is the
        // side effect that scoping the query in the database removes: the
        // non-owner's request never loads the entity, so it cannot expire the
        // hold, release the seats, or stage an outbox event on it.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "C", "seatNumber", 3, "priceCents", 4000)));
        ResponseEntity<Map> owned = postReservation(created.eventId(), created.seatIds(), 504L);
        long reservationId = ((Number) owned.getBody().get("id")).longValue();

        assertThat(getReservation(reservationId, 505L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(getReservation(reservationId, 504L).getBody())
                .containsEntry("status", "PENDING_PAYMENT");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class,
                created.seatIds().get(0)))
                .isEqualTo("HELD");
        assertThat(outboxExpiredCount(reservationId)).isZero();
    }

    @Test
    void aCustomerCanListOnlyTheirOwnReservations() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Stalls", "row", "A", "seatNumber", 1, "priceCents", 5000),
                Map.of("section", "Stalls", "row", "A", "seatNumber", 2, "priceCents", 6000)));
        postReservation(created.eventId(), created.seatIds(), 506L);
        postReservation(created.eventId(), List.of(created.seatIds().get(0)), 507L);

        ResponseEntity<List> mine = listReservations(506L, "");

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map> rows = (List<Map>) mine.getBody();
        assertThat(rows).extracting(row -> row.get("customerId")).containsExactly(506);
    }

    @Test
    void aCustomerCannotListAnotherCustomersReservationsByAskingForThem() {
        // The list half of the same bypass. The query parameter is refused rather
        // than honoured, so 7 does not receive 99's reservations and does not
        // receive a silent empty list that a client might report as "I have none".
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "D", "seatNumber", 4, "priceCents", 7000)));
        postReservation(created.eventId(), created.seatIds(), 508L);

        ResponseEntity<String> theirs = listReservationsAsText(509L, "?customerId=508");

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(theirs.getBody())
                .contains("\"status\":400")
                .doesNotContain("PENDING_PAYMENT");
    }

    @Test
    void aReservationReadWithNoCustomerHeaderIsRefused() {
        // Nothing reaches this service without the gateway having derived that
        // header, so its absence means the request did not come through the
        // gateway — and this route refuses to be a way in for anything else.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Upper Circle", "row", "E", "seatNumber", 5, "priceCents", 3000)));
        ResponseEntity<Map> owned = postReservation(created.eventId(), created.seatIds(), 510L);
        long reservationId = ((Number) owned.getBody().get("id")).longValue();

        assertThat(getReservationWithoutCustomerHeader(reservationId).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> listRefused =
                rest.exchange(
                        "/api/v1/reservations?customerId=99",
                        HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()),
                        String.class);
        assertThat(listRefused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listRefused.getBody()).contains("X-Customer-Id");
    }

    @Test
    void reservationCreationStillWorksForACustomer() {
        // The write path is unchanged by ADR 014, and asserted here so the
        // ownership fix cannot have quietly cost the platform its ability to sell a
        // seat: booking is still scoped by the same header.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Orchestra", "row", "F", "seatNumber", 6, "priceCents", 15000)));

        ResponseEntity<Map> created_ = postReservation(created.eventId(), created.seatIds(), 511L);

        assertThat(created_.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created_.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(created_.getBody()).containsEntry("customerId", 511);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class,
                created.seatIds().get(0)))
                .isEqualTo("HELD");
    }

    @Test
    void expiredHoldReleasesSeatAndExpiresReservationForReReservation() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Mezzanine", "row", "A", "seatNumber", 1, "priceCents", 4000)));

        ResponseEntity<Map> first =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 21L);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) first.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", seatId);
        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);

        holdExpirer.expire();

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("AVAILABLE");
        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId);
        assertThat(reservationStatus).isEqualTo("EXPIRED");

        // The same transaction that flipped the reservation to EXPIRED writes exactly one
        // ReservationExpired outbox row, whose amount is the reservation's own seat pricing
        // (4000) — mirroring the ReservationCancelled/ReservationConfirmed wire convention.
        assertThat(outboxExpiredCount(reservationId)).isEqualTo(1);
        String expiredPayload = jdbc.queryForObject(
                "SELECT payload FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        // The payload column is Postgres jsonb, which re-orders keys and re-formats with spaces;
        // collapse whitespace so assertions are formatting-independent.
        assertThat(compactJson(expiredPayload))
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":21")
                .contains("\"eventId\":" + created.eventId())
                .contains("\"seatIds\":[" + seatId + "]")
                .contains("\"amountCents\":4000");
        String stagedCorrelationId = jdbc.queryForObject(
                "SELECT correlation_id FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        assertThat(stagedCorrelationId).as("the sweep stages a correlation id").isNotBlank();

        outboxPublisher.poll();
        ConsumerRecord<String, String> expired = awaitOnTopic(
                new UUID(0L, reservationId).toString(), "reservation.ReservationExpired");
        assertThat(expired.value())
                .as("the published ReservationExpired envelope carries the sweep's release amount")
                .contains("\"amountCents\":4000")
                .contains("\"reservationId\":" + reservationId);
        assertThat(expired.headers().lastHeader(CORRELATION_HEADER)).isNotNull();
        assertThat(new String(expired.headers().lastHeader(CORRELATION_HEADER).value()))
                .isEqualTo(stagedCorrelationId);

        // A later read must not re-publish: the sweep already committed the EXPIRED transition, so
        // the read path's expireIfOverdue no-ops and the count stays at exactly one.
        ResponseEntity<Map> reread = getReservation(reservationId, 21L);
        assertThat(reread.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reread.getBody().get("status")).isEqualTo("EXPIRED");
        assertThat(outboxExpiredCount(reservationId))
                .as("a read after the sweep must not re-publish ReservationExpired")
                .isEqualTo(1);

        ResponseEntity<Map> again =
                postReservation(created.eventId(), List.of(seatId), 22L);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void expiringReservationDoesNotReleaseSeatWhoseHoldIsStillLive() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Terrace", "row", "A", "seatNumber", 1, "priceCents", 3000)));

        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 31L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        String heldUntil = jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", String.class, seatId);

        holdExpirer.expire();

        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId);
        assertThat(reservationStatus).isEqualTo("EXPIRED");

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("HELD");
        String stillHeldUntil = jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(stillHeldUntil).isEqualTo(heldUntil);

        // The reservation is still past its expiresAt, so it expires and still publishes — but
        // the re-held live seat was released by nobody, so ReservationExpired must report the
        // truth: empty seat list, zero amount. It never claims the reservation's original seats.
        assertThat(outboxExpiredCount(reservationId)).isEqualTo(1);
        String expiredPayload = jdbc.queryForObject(
                "SELECT payload FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        assertThat(compactJson(expiredPayload)).contains("\"seatIds\":[]").contains("\"amountCents\":0");
    }

    @Test
    void staleExpirySaveIsRejectedByOptimisticLockKeepingTheReHold() {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 1, "priceCents", 2500)));

        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 41L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE customer_id = 41");
        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", seatId);

        SeatEntity stale = seatRepository.findById(seatId).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo(SeatStatus.HELD);

        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() + interval '10 minutes', version = version + 1 WHERE id = ?",
                seatId);

        assertThat(stale.releaseHoldIfLapsed(Instant.now())).isTrue();

        assertThatThrownBy(() -> seatRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        String status = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(status).isEqualTo("HELD");
    }

    @Test
    void concurrentReHoldIsNotUndoneByExpirerTransaction() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 2, "priceCents", 2500)));
        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 42L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE customer_id = 42");
        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", seatId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate(
                    "UPDATE reservation.seats SET hold_expires_at = now() + interval '10 minutes', "
                            + "version = version + 1 WHERE id = " + seatId);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> expiry = executor.submit(() -> holdExpirer.expire());
                awaitExpirerBlockedOnSeatLock(connection);
                connection.commit();
                assertThatThrownBy(() -> expiry.get(30, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
            } finally {
                executor.shutdownNow();
            }
        }

        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE customer_id = 42", String.class);
        assertThat(reservationStatus).isEqualTo("PENDING_PAYMENT");

        // The stale-seat optimistic-lock failure rolled the whole sweep back as one unit of work:
        // the reservation never stayed EXPIRED, the seat stayed HELD, and the ReservationExpired
        // outbox row staged in the same transaction was rolled back with it — no orphan event.
        assertThat(outboxExpiredCount(reservationId))
                .as("the rolled-back racing sweep must leave no ReservationExpired outbox row")
                .isZero();

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("HELD");

        holdExpirer.expire();

        reservationStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE customer_id = 42", String.class);
        assertThat(reservationStatus).isEqualTo("EXPIRED");
        seatStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("HELD");
        String holdUntil = jdbc.queryForObject(
                "SELECT hold_expires_at FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(holdUntil).isNotNull();

        // The later, clean sweep expires the reservation and writes exactly its one
        // ReservationExpired row — with the re-held seat correctly reported as not released.
        assertThat(outboxExpiredCount(reservationId)).isEqualTo(1);
        String expiredPayload = jdbc.queryForObject(
                "SELECT payload FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        assertThat(compactJson(expiredPayload)).contains("\"seatIds\":[]").contains("\"amountCents\":0");
    }

    @Test
    void twoConcurrentExpirersPublishExactlyOneReservationExpired() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 3, "priceCents", 2500)));
        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 43L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?",
                seatId);

        // Two scheduler instances racing the same overdue reservation. The single @Version gate on
        // the reservation (as on the seats) lets exactly one transition commit: a colliding loser
        // rolls its whole sweep back with ObjectOptimisticLockingFailureException, and a loser
        // that loads after the winner committed selects nothing at all. Either way the committed
        // state is exactly one EXPIRED transition and one ReservationExpired outbox row — never two.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    holdExpirer.expire();
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures)
                .as("a raced-away sweep may fail only with the optimistic-lock guard")
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(ObjectOptimisticLockingFailureException.class));

        String terminalStatus = jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId);
        assertThat(terminalStatus).isEqualTo("EXPIRED");
        String terminalSeat = jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId);
        assertThat(terminalSeat).isEqualTo("AVAILABLE");
        assertThat(outboxExpiredCount(reservationId))
                .as("two sweep instances must commit at most one ReservationExpired row")
                .isEqualTo(1);
    }

    @Test
    void getOfOverdueReservationExpiresItAndPublishesExactlyOneReservationExpired() {
        // The regression this test exists for: a GET/list that expires an overdue reservation
        // used to skip the outbox entirely — the reservation left the sweep's PENDING_PAYMENT
        // query, so ReservationExpired was never published. The read path must behave exactly
        // like the sweep: same transition, same event, same correlation propagation, same
        // exactly-one guarantee when the sweep runs afterward.
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 2, "priceCents", 4000)));
        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 23L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?",
                seatId);

        ResponseEntity<Map> response = getReservation(reservationId, 23L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("EXPIRED");

        // The id this request travelled under, read back off the response rather
        // than dictated by the test. The filter only propagates a caller's value
        // when it is a shape the platform recognises, so supplying one here would
        // be asserting about a header the test does not get to choose.
        String correlationId = response.getHeaders().getFirst(CORRELATION_HEADER);
        assertThat(correlationId).isNotBlank();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId))
                .isEqualTo("AVAILABLE");
        assertThat(outboxExpiredCount(reservationId))
                .as("the read path must stage exactly one ReservationExpired row")
                .isEqualTo(1);
        String expiredPayload = jdbc.queryForObject(
                "SELECT payload FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        assertThat(compactJson(expiredPayload))
                .contains("\"reservationId\":" + reservationId)
                .contains("\"customerId\":23")
                .contains("\"eventId\":" + created.eventId())
                .contains("\"seatIds\":[" + seatId + "]")
                .contains("\"amountCents\":4000");
        String stagedCorrelationId = jdbc.queryForObject(
                "SELECT correlation_id FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                String.class, reservationId);
        assertThat(stagedCorrelationId)
                .as("the read-path expiry stages the request's correlation id")
                .isEqualTo(correlationId);

        outboxPublisher.poll();
        ConsumerRecord<String, String> expired = awaitOnTopic(
                new UUID(0L, reservationId).toString(), "reservation.ReservationExpired");
        assertThat(expired.value())
                .contains("\"amountCents\":4000")
                .contains("\"reservationId\":" + reservationId);
        assertThat(new String(expired.headers().lastHeader(CORRELATION_HEADER).value()))
                .as("the wire correlation must echo the request's correlation id")
                .isEqualTo(correlationId);

        // The catch-up sweep must not duplicate what the read path already published: it finds no
        // PENDING_PAYMENT reservation and stages nothing.
        holdExpirer.expire();
        assertThat(outboxExpiredCount(reservationId))
                .as("a later sweep must not re-publish ReservationExpired")
                .isEqualTo(1);
    }

    @Test
    void getExpiryRacingScheduledExpiryWritesExactlyOneReservationExpired() throws Exception {
        CreatedEvent created = postEvent(List.of(
                Map.of("section", "Gallery", "row", "A", "seatNumber", 4, "priceCents", 2500)));
        ResponseEntity<Map> reservation =
                postReservation(created.eventId(), List.of(created.seatIds().get(0)), 44L);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long reservationId = ((Number) reservation.getBody().get("id")).longValue();
        long seatId = created.seatIds().get(0);

        jdbc.update(
                "UPDATE reservation.reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        jdbc.update(
                "UPDATE reservation.seats SET hold_expires_at = now() - interval '1 minute' WHERE id = ?",
                seatId);

        // Race the read-path expiry (an in-service GET, running its own transaction) against the
        // scheduled sweep's transaction. The single @Version gates let exactly one commit the
        // EXPIRED transition; whichever loses rolls its whole unit back (EXPIRED mutation, seat
        // release, staged ReservationExpired row), so the two paths jointly commit exactly one
        // event — never two, never none.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Void> read = executor.submit(() -> {
                reservations.get(reservationId, 44L);
                return null;
            });
            Future<Void> sweep = executor.submit(() -> {
                holdExpirer.expire();
                return null;
            });
            for (Future<Void> future : List.of(read, sweep)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures)
                .as("a raced-away expiry may fail only with the optimistic-lock guard")
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(ObjectOptimisticLockingFailureException.class));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation.reservations WHERE id = ?", String.class, reservationId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation.seats WHERE id = ?", String.class, seatId))
                .isEqualTo("AVAILABLE");
        assertThat(outboxExpiredCount(reservationId))
                .as("read-path and scheduled expiry must jointly commit exactly one ReservationExpired row")
                .isEqualTo(1);
    }

    private void awaitExpirerBlockedOnSeatLock(Connection connection)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE granted = false "
                                + "AND transactionid = pg_current_xact_id()::xid "
                                + "AND pid <> pg_backend_pid()")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
                "expire() never blocked on the held seat lock — the race window was not entered");
    }

    private CreatedEvent postEvent(List<Map<String, Object>> seats) {
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/events", Map.of(
                "name", "Opening Night",
                "venue", "Metropolitan Opera",
                "eventDate", "2026-11-01T19:30:00Z",
                "seats", seats), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        List<Number> seatIds = (List<Number>) response.getBody().get("seatIds");
        return new CreatedEvent(((Number) response.getBody().get("eventId")).longValue(),
                seatIds.stream().map(Number::longValue).toList());
    }

    /**
     * A reservation read as {@code customerId} — the identity the gateway would
     * have derived from the token, and the only thing a read is scoped by
     * (ADR 014). Reads go through here rather than through
     * {@code rest.getForEntity} so that no test in this file can accidentally
     * assert against an unscoped route.
     */
    private ResponseEntity<Map> getReservation(long reservationId, long customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        return rest.exchange(
                "/api/v1/reservations/" + reservationId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<List> listReservations(long customerId, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        return rest.exchange(
                "/api/v1/reservations" + query, HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    /** The same read with no identity header at all, as if the gateway were not in front of it. */
    /**
     * The Customer ids the ownership tests book against, so they can be cleaned up
     * afterwards.
     *
     * <p>These tests share one database with every other test in this class, and
     * several of those assert on global counts — {@code count(*) FROM seats WHERE
     * status = 'HELD'} among them. A test that leaves a hold behind therefore
     * breaks a test that has nothing to do with ownership, so the reservations
     * these tests create are removed on the way out rather than left for whoever
     * runs next. The ids are deliberately ones no other test uses, which is what
     * makes the cleanup able to identify them.
     */
    private static final List<Long> OWNERSHIP_TEST_CUSTOMERS =
            List.of(501L, 502L, 503L, 504L, 505L, 506L, 507L, 508L, 509L, 510L, 511L);

    @AfterEach
    void releaseTheHoldsTheOwnershipTestsTook() {
        for (long customerId : OWNERSHIP_TEST_CUSTOMERS) {
            // Seats first: the join rows still reference the reservation, and a
            // seat left HELD is what the global count assertions would trip on.
            jdbc.update(
                    """
                    UPDATE reservation.seats SET status = 'AVAILABLE', hold_expires_at = NULL
                    WHERE id IN (
                        SELECT seat_id FROM reservation.reservation_seats
                        WHERE reservation_id IN (
                            SELECT id FROM reservation.reservations WHERE customer_id = ?))
                    """,
                    customerId);
            jdbc.update("DELETE FROM reservation.reservation_seats WHERE reservation_id IN "
                    + "(SELECT id FROM reservation.reservations WHERE customer_id = ?)", customerId);
            jdbc.update("DELETE FROM reservation.outbox_events WHERE aggregate_id IN "
                    + "(SELECT id FROM reservation.reservations WHERE customer_id = ?)", customerId);
            jdbc.update("DELETE FROM reservation.reservations WHERE customer_id = ?", customerId);
        }
    }

    private ResponseEntity<Map> getReservationWithoutCustomerHeader(long reservationId) {
        return rest.exchange(
                "/api/v1/reservations/" + reservationId,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Map.class);
    }

    private ResponseEntity<List> listReservationsWithoutCustomerHeader(String query) {
        return rest.exchange(
                "/api/v1/reservations" + query,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                List.class);
    }

    /**
     * A list read whose response is kept as raw text, because the interesting
     * cases here are refusals and an error body is an object rather than a list.
     */
    private ResponseEntity<String> listReservationsAsText(long customerId, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        return rest.exchange(
                "/api/v1/reservations" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<Map> postReservation(long eventId, List<Long> seatIds, long customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CUSTOMER_HEADER, String.valueOf(customerId));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("eventId", eventId, "seatIds", seatIds), headers);
        return rest.postForEntity("/api/v1/reservations", entity, Map.class);
    }

    private void awaitOutboxPublished(int expectedRows) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer published = jdbc.queryForObject(
                    "SELECT count(*) FROM reservation.outbox_events WHERE published_at IS NOT NULL", Integer.class);
            if (published != null && published >= expectedRows) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting outbox publish", ex);
            }
        }
        throw new AssertionError("Outbox event was not marked published within 10s");
    }

    private int outboxExpiredCount(long reservationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservation.outbox_events WHERE aggregate_id = ? AND event_type = 'reservation.ReservationExpired'",
                Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private static String compactJson(String json) {
        return json.replaceAll("\\s+", "");
    }

    private ConsumerRecord<String, String> awaitOnTopic(String expectedKey, String mustContain) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(OutboxPublisher.RESERVATION_EVENTS_TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(OutboxPublisher.RESERVATION_EVENTS_TOPIC)) {
                    if (expectedKey.equals(record.key()) && record.value().contains(mustContain)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No " + mustContain + " message arrived for key " + expectedKey);
    }

    private ConsumerRecord<String, String> awaitMessage(long reservationId) {
        String expectedKey = new UUID(0L, reservationId).toString();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(OutboxPublisher.RESERVATION_EVENTS_TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2)).records(OutboxPublisher.RESERVATION_EVENTS_TOPIC)) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No ReservationCreated message arrived for reservation " + reservationId);
    }

    private record CreatedEvent(long eventId, List<Long> seatIds) {}
}
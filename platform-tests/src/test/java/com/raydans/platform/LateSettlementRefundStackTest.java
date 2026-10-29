package com.raydans.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #52 on the real stack: every service, the real topics, the real serialization, and
 * nothing in between written by a test. A late {@code PaymentSucceeded} is refunded
 * exactly once and the Customer is told; a late {@code PaymentFailed}, a
 * {@code CONFIRMED} Reservation and a {@code CANCELLED} one are left alone. Then every
 * consumer group is rewound to offset zero, as a restart from lost offsets would, and
 * the same end state must hold.
 *
 * <p>Runs against a compose stack that is already up with a short Hold
 * ({@code HOLD_DURATION=PT30S}); it stops and starts services itself, through
 * {@code docker compose}, to make a payment late. Tagged {@code stack-test}, so it runs
 * only under {@code -Pstack-test}. The commands are in {@code docs/stack-test.md}.
 */
@Tag("stack-test")
class LateSettlementRefundStackTest {

    private static final String BASE_URL = System.getProperty("stackTest.baseUrl", "http://localhost:8080");
    private static final int CHEAP_CENTS = 5000;
    /** At or over the compose stack's decline threshold (50000), so the payment fails. */
    private static final int DECLINED_CENTS = 60000;

    private static final Duration READY_WITHIN = Duration.ofMinutes(3);
    private static final Duration SETTLES_WITHIN = Duration.ofMinutes(3);
    private static final Duration LONGEST_HOLD_ACCEPTED = Duration.ofMinutes(2);

    private static final List<String> CONSUMER_GROUPS =
            List.of("reservation-service", "payment-service", "notification-service");
    private static final List<String> TOPICS = List.of(
            "reservation.events.v1", "reservation.events.v1.dlt", "payment.events.v1", "payment.events.v1.dlt");
    private static final List<String> DEAD_LETTER_TOPICS = List.of("reservation.events.v1.dlt", "payment.events.v1.dlt");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    private String organizer;
    private String customer;

    @Test
    void aLateSuccessIsRefundedAndToldOnceTheRestOfTheSagaIsUntouchedAndAReplayChangesNothing() throws Exception {
        organizer = login("organizer", password("DEMO_ORGANIZER_PASSWORD"));
        customer = login("customer", password("DEMO_CUSTOMER_PASSWORD"));
        awaitServices();

        // reservation-service provisions all four topics at startup (ADR 008); nothing
        // here may depend on the broker auto-creating one.
        Map<String, Integer> partitions = topicPartitions();
        assertThat(partitions).as("topics provisioned in the running stack").containsKeys(TOPICS.toArray(String[]::new));
        Map<String, Long> deadLettersBefore = endOffsets(DEAD_LETTER_TOPICS);

        long eventId = createEvent();
        List<Long> seats = seatIds(eventId);
        long confirmedSeat = seats.get(0);
        long cancelledSeat = seats.get(1);
        long lateSuccessSeat = seats.get(2);
        long lateFailureSeat = seats.get(3);

        // Controls, with the money step running: flows 1 and 2 of the walkthrough.
        long confirmed = reserve(eventId, confirmedSeat);
        long cancelled = reserve(eventId, cancelledSeat);
        awaitUntil(() -> "CONFIRMED".equals(reservationStatus(confirmed)), "reservation " + confirmed + " CONFIRMED");
        awaitUntil(() -> "CANCELLED".equals(reservationStatus(cancelled)), "reservation " + cancelled + " CANCELLED");

        // Flow 3: with payment-service down the Holds lapse; brought back, it settles both late.
        compose("stop", "payment-service");
        long lateSuccess;
        long lateFailure;
        try {
            lateSuccess = reserve(eventId, lateSuccessSeat);
            lateFailure = reserve(eventId, lateFailureSeat);
            awaitUntil(() -> "EXPIRED".equals(reservationStatus(lateSuccess)), "reservation " + lateSuccess + " EXPIRED");
            awaitUntil(() -> "EXPIRED".equals(reservationStatus(lateFailure)), "reservation " + lateFailure + " EXPIRED");
        } finally {
            compose("start", "payment-service");
        }
        awaitUntil(() -> "REFUNDED".equals(paymentStatus(lateSuccess)), "payment " + lateSuccess + " REFUNDED");
        awaitUntil(() -> "FAILED".equals(paymentStatus(lateFailure)), "payment " + lateFailure + " FAILED");
        awaitQuiet();

        Cast cast = new Cast(eventId, confirmed, cancelled, lateSuccess, lateFailure,
                Map.of(confirmed, confirmedSeat, cancelled, cancelledSeat, lateSuccess, lateSuccessSeat,
                        lateFailure, lateFailureSeat));
        assertEndState(cast, "after the late settlement");
        assertThat(endOffsets(DEAD_LETTER_TOPICS)).as("nothing was dead-lettered").isEqualTo(deadLettersBefore);
        print("  after late settlement: " + describe(cast) + '\n');

        // Replay: every consumer group back to offset zero, as a restart that lost its
        // offsets would see. Every event this stack has ever carried is delivered again.
        compose("stop", "reservation-service", "payment-service", "notification-service");
        try {
            for (String group : CONSUMER_GROUPS) {
                resetToEarliest(group);
            }
        } finally {
            compose("start", "reservation-service", "payment-service", "notification-service");
        }
        awaitServices();
        awaitQuiet();

        assertEndState(cast, "after replaying every topic from offset zero");
        assertThat(endOffsets(DEAD_LETTER_TOPICS)).as("a replay dead-letters nothing").isEqualTo(deadLettersBefore);
        print("  after full replay:     " + describe(cast) + '\n');
    }

    private record Cast(
            long eventId, long confirmed, long cancelled, long lateSuccess, long lateFailure, Map<Long, Long> seatOf) {}

    private void assertEndState(Cast cast, String when) throws Exception {
        // Through the API, as the Customer sees it.
        assertReservation(cast.lateSuccess(), "EXPIRED", cast, when);
        assertReservation(cast.lateFailure(), "EXPIRED", cast, when);
        assertReservation(cast.confirmed(), "CONFIRMED", cast, when);
        assertReservation(cast.cancelled(), "CANCELLED", cast, when);

        JsonNode refunded = payment(cast.lateSuccess());
        assertThat(refunded.path("status").asText()).as("late success %s", when).isEqualTo("REFUNDED");
        assertThat(refunded.path("settledAt").isNull()).isFalse();
        assertThat(refunded.path("refundedAt").isNull()).as("refundedAt set %s", when).isFalse();
        assertPayment(cast.lateFailure(), "FAILED", when);
        assertPayment(cast.confirmed(), "SUCCEEDED", when);
        assertPayment(cast.cancelled(), "FAILED", when);

        Map<Long, List<String>> inbox = inboxTypes();
        assertThat(inbox.get(cast.lateSuccess()))
                .as("late success inbox, newest first, %s", when)
                .containsExactly("PAYMENT_REFUNDED", "RESERVATION_EXPIRED");
        assertThat(inbox.get(cast.lateFailure())).as("late failure inbox %s", when)
                .containsExactly("RESERVATION_EXPIRED");
        assertThat(inbox.get(cast.confirmed())).as("confirmed inbox %s", when)
                .containsExactly("RESERVATION_CONFIRMED");
        assertThat(inbox.get(cast.cancelled())).as("cancelled inbox %s", when)
                .containsExactly("RESERVATION_CANCELLED");

        // And in the database, which counts what the API could only summarise.
        String ids = cast.lateSuccess() + "," + cast.lateFailure() + "," + cast.confirmed() + "," + cast.cancelled();
        assertThat(rowsByReservation("SELECT aggregate_id, count(*) FROM reservation.outbox_events "
                        + "WHERE event_type = 'reservation.LatePaymentRejected' AND aggregate_id IN (" + ids + ") "
                        + "GROUP BY aggregate_id"))
                .as("LatePaymentRejected rows %s", when)
                .containsExactlyEntriesOf(Map.of(cast.lateSuccess(), 1L));
        assertThat(rowsByReservation("SELECT aggregate_id, count(*) FROM payment.outbox_events "
                        + "WHERE event_type = 'payment.PaymentRefunded' AND aggregate_id IN (" + ids + ") "
                        + "GROUP BY aggregate_id"))
                .as("PaymentRefunded rows %s", when)
                .containsExactlyEntriesOf(Map.of(cast.lateSuccess(), 1L));
        assertThat(rowsByReservation("SELECT reservation_id, count(*) FROM payment.payments "
                        + "WHERE status = 'REFUNDED' AND reservation_id IN (" + ids + ") GROUP BY reservation_id"))
                .as("REFUNDED Payments %s", when)
                .containsExactlyEntriesOf(Map.of(cast.lateSuccess(), 1L));
        assertThat(rowsByReservation("SELECT reservation_id, count(*) FROM payment.payments "
                        + "WHERE reservation_id IN (" + ids + ") GROUP BY reservation_id"))
                .as("one Payment per Reservation %s", when)
                .containsOnlyKeys(cast.lateSuccess(), cast.lateFailure(), cast.confirmed(), cast.cancelled())
                .allSatisfy((reservation, count) -> assertThat(count).isEqualTo(1L));
        assertThat(rowsByReservation("SELECT reservation_id, count(*) FROM notification.notifications "
                        + "WHERE type = 'PAYMENT_REFUNDED' AND reservation_id IN (" + ids + ") GROUP BY reservation_id"))
                .as("PAYMENT_REFUNDED Notifications %s", when)
                .containsExactlyEntriesOf(Map.of(cast.lateSuccess(), 1L));
    }

    private void assertReservation(long reservationId, String status, Cast cast, String when) throws Exception {
        assertThat(reservationStatus(reservationId)).as("reservation %d %s", reservationId, when).isEqualTo(status);
        String seatStatus = seatStatus(cast.eventId(), cast.seatOf().get(reservationId));
        String expected = "CONFIRMED".equals(status) ? "SOLD" : "AVAILABLE";
        assertThat(seatStatus).as("seat of reservation %d %s", reservationId, when).isEqualTo(expected);
    }

    private void assertPayment(long reservationId, String status, String when) throws Exception {
        JsonNode payment = payment(reservationId);
        assertThat(payment.path("status").asText()).as("payment %d %s", reservationId, when).isEqualTo(status);
        assertThat(payment.has("refundedAt")).isTrue();
        assertThat(payment.path("refundedAt").isNull()).as("payment %d not refunded %s", reservationId, when).isTrue();
    }

    private String describe(Cast cast) throws Exception {
        Map<String, String> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : Map.of(
                "lateSuccess", cast.lateSuccess(), "lateFailure", cast.lateFailure(),
                "confirmed", cast.confirmed(), "cancelled", cast.cancelled()).entrySet()) {
            long id = entry.getValue();
            summary.put(entry.getKey() + "#" + id,
                    reservationStatus(id) + "/" + paymentStatus(id) + "/" + inboxTypes().get(id));
        }
        return summary.toString();
    }

    // --- the stack's quiet point ---------------------------------------------------------

    /**
     * Nothing left to happen: no outbox row unpublished and no consumer group behind its
     * topics, twice in a row. Asserting before that point would check a moment in the
     * saga rather than its result.
     */
    private void awaitQuiet() throws Exception {
        Instant deadline = Instant.now().plus(SETTLES_WITHIN);
        int quietChecks = 0;
        while (quietChecks < 2) {
            boolean quiet = unpublishedOutboxRows() == 0 && CONSUMER_GROUPS.stream().allMatch(this::caughtUp);
            quietChecks = quiet ? quietChecks + 1 : 0;
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The stack did not settle within " + SETTLES_WITHIN + ": unpublished outbox rows "
                        + unpublishedOutboxRows() + ", consumer groups:\n" + kafka("kafka-consumer-groups.sh",
                        "--describe", "--all-groups"));
            }
            Thread.sleep(3000);
        }
    }

    private long unpublishedOutboxRows() {
        return Long.parseLong(psql("SELECT (SELECT count(*) FROM reservation.outbox_events WHERE published_at IS NULL)"
                + " + (SELECT count(*) FROM payment.outbox_events WHERE published_at IS NULL)").get(0));
    }

    /** Every partition the group is assigned has a committed offset and no lag. */
    private boolean caughtUp(String group) {
        String described = kafka("kafka-consumer-groups.sh", "--describe", "--group", group);
        List<String[]> rows = described.lines()
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns.length >= 6 && columns[0].equals(group))
                .toList();
        return !rows.isEmpty() && rows.stream().allMatch(columns -> columns[5].equals("0"));
    }

    private void resetToEarliest(String group) throws InterruptedException {
        // A stopped service leaves its group at once on a clean shutdown, but the broker can
        // take a moment to agree; a reset on a group with members is refused, so retry.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(1));
        while (true) {
            String output;
            try {
                output = kafka("kafka-consumer-groups.sh", "--group", group, "--all-topics",
                        "--reset-offsets", "--to-earliest", "--execute");
            } catch (IllegalStateException refused) {
                output = refused.getMessage();
            }
            if (!output.contains("Error") && !output.contains("inactive")) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Could not rewind " + group + ":\n" + output);
            }
            Thread.sleep(2000);
        }
    }

    private Map<String, Integer> topicPartitions() {
        Map<String, Integer> partitions = new HashMap<>();
        kafka("kafka-topics.sh", "--describe").lines()
                .filter(line -> line.startsWith("Topic:") && line.contains("PartitionCount:"))
                .forEach(line -> {
                    String[] parts = line.split("\\s+");
                    partitions.put(parts[1], Integer.parseInt(parts[5]));
                });
        return partitions;
    }

    private Map<String, Long> endOffsets(List<String> topics) {
        Map<String, Long> offsets = new LinkedHashMap<>();
        for (String topic : topics) {
            long end = kafka("kafka-get-offsets.sh", "--topic", topic).lines()
                    .filter(line -> line.startsWith(topic + ":"))
                    .mapToLong(line -> Long.parseLong(line.substring(line.lastIndexOf(':') + 1)))
                    .sum();
            offsets.put(topic, end);
        }
        return offsets;
    }

    // --- the API -------------------------------------------------------------------------

    private long createEvent() throws IOException, InterruptedException {
        List<Map<String, Object>> seats = List.of(
                Map.of("section", "REFUND", "row", "1", "seatNumber", 1, "priceCents", CHEAP_CENTS),
                Map.of("section", "REFUND", "row", "1", "seatNumber", 2, "priceCents", DECLINED_CENTS),
                Map.of("section", "REFUND", "row", "1", "seatNumber", 3, "priceCents", CHEAP_CENTS),
                Map.of("section", "REFUND", "row", "1", "seatNumber", 4, "priceCents", DECLINED_CENTS));
        JsonNode created = expect(201, authorized(organizer, "/api/v1/events")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "name", "Late-settlement refund stack test",
                        "venue", "platform-tests",
                        "eventDate", Instant.now().plus(Duration.ofDays(30)).toString(),
                        "seats", seats))))
                .build());
        return created.path("eventId").asLong();
    }

    /** The Event's Seats in the order they were created, which is the order of their ids. */
    private List<Long> seatIds(long eventId) throws IOException, InterruptedException {
        List<Long> ids = new ArrayList<>();
        expect(200, authorized(organizer, "/api/v1/events/" + eventId + "/seats").GET().build())
                .forEach(seat -> ids.add(seat.path("id").asLong()));
        return ids.stream().sorted().toList();
    }

    private long reserve(long eventId, long seatId) throws IOException, InterruptedException {
        JsonNode reservation = expect(201, authorized(customer, "/api/v1/reservations")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("eventId", eventId, "seatIds", List.of(seatId)))))
                .build());
        Duration hold = Duration.between(Instant.now(), Instant.parse(reservation.path("expiresAt").asText()));
        if (hold.compareTo(LONGEST_HOLD_ACCEPTED) > 0) {
            throw new IllegalStateException("The stack's Hold is " + hold.toMinutes() + " min; this test needs a short "
                    + "one. Restart it with HOLD_DURATION=PT30S (docs/stack-test.md).");
        }
        return reservation.path("id").asLong();
    }

    private String reservationStatus(long reservationId) {
        return get(customer, "/api/v1/reservations/" + reservationId).path("status").asText();
    }

    private String seatStatus(long eventId, long seatId) throws IOException, InterruptedException {
        for (JsonNode seat : expect(200, authorized(organizer, "/api/v1/events/" + eventId + "/seats").GET().build())) {
            if (seat.path("id").asLong() == seatId) {
                return seat.path("status").asText();
            }
        }
        throw new AssertionError("Seat " + seatId + " is not on Event " + eventId);
    }

    /** The Payment's status, or {@code "absent"} while payment-service has not created it yet. */
    private String paymentStatus(long reservationId) {
        try {
            HttpResponse<String> response = http.send(
                    authorized(customer, "/api/v1/payments/" + reservationId).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? json.readTree(response.body()).path("status").asText() : "absent";
        } catch (IOException ex) {
            return "unreachable";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode payment(long reservationId) {
        return get(customer, "/api/v1/payments/" + reservationId);
    }

    /** The Customer's inbox, newest first, as the types told per Reservation. */
    private Map<Long, List<String>> inboxTypes() {
        Map<Long, List<String>> byReservation = new HashMap<>();
        get(customer, "/api/v1/notifications?limit=200").path("items").forEach(item -> byReservation
                .computeIfAbsent(item.path("reservationId").asLong(), id -> new ArrayList<>())
                .add(item.path("type").asText()));
        return byReservation;
    }

    private JsonNode get(String token, String path) {
        try {
            return expect(200, authorized(token, path).GET().build());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** The gateway answers before the services behind it do; wait for every route this test reads. */
    private void awaitServices() throws InterruptedException {
        awaitUntil(() -> statusOf(organizer, "/api/v1/events") == 200
                        && statusOf(customer, "/api/v1/reservations") == 200
                        && statusOf(customer, "/api/v1/notifications?limit=1") == 200
                        && statusOf(customer, "/api/v1/payments/999999999") == 404,
                "every service to answer through the gateway", READY_WITHIN);
    }

    private int statusOf(String token, String path) {
        try {
            return http.send(authorized(token, path).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException ex) {
            return -1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private String login(String username, String password) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("username", username, "password", password))))
                .build();
        Instant deadline = Instant.now().plus(READY_WITHIN);
        while (true) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Logging in as " + username + " answered "
                            + response.statusCode() + ": " + response.body());
                }
                return json.readTree(response.body()).path("token").asText();
            } catch (IOException ex) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Nothing answers at " + BASE_URL
                            + "; this test runs against a compose stack that is already up (docs/stack-test.md)", ex);
                }
                Thread.sleep(2000);
            }
        }
    }

    private HttpRequest.Builder authorized(String token, String path) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode expect(int status, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != status) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " answered "
                    + response.statusCode() + ", expected " + status + ": " + response.body());
        }
        return json.readTree(response.body());
    }

    private void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        awaitUntil(condition, what, SETTLES_WITHIN);
    }

    private void awaitUntil(BooleanSupplier condition, String what, Duration within) throws InterruptedException {
        Instant deadline = Instant.now().plus(within);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + within + " waiting for " + what);
            }
            Thread.sleep(1000);
        }
    }

    // --- docker compose ------------------------------------------------------------------

    private Map<Long, Long> rowsByReservation(String sql) {
        Map<Long, Long> counts = new HashMap<>();
        for (String line : psql(sql)) {
            String[] columns = line.split("\\|");
            counts.put(Long.parseLong(columns[0]), Long.parseLong(columns[1]));
        }
        return counts;
    }

    /** Postgres publishes no port (ADR 013), so it is asked from inside its container. */
    private static List<String> psql(String sql) {
        return compose("exec", "-T", "postgres", "psql", "-U", "platform", "-d", "platform", "-tA",
                "-v", "ON_ERROR_STOP=1", "-c", sql).lines().filter(line -> !line.isBlank()).toList();
    }

    /** Kafka publishes no port either; its own CLI, inside its container, against the internal listener. */
    private static String kafka(String tool, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "exec", "-T", "kafka", "/opt/kafka/bin/" + tool, "--bootstrap-server", "kafka:9092"));
        command.addAll(List.of(arguments));
        return compose(command.toArray(String[]::new));
    }

    /**
     * One {@code docker compose} command from the repository root, bounded in time. Output
     * goes to a file rather than a pipe this thread reads, so a command that hangs with its
     * output open still reaches the timeout (the same reasoning as the load test's psql).
     */
    private static String compose(String... arguments) {
        List<String> command = new ArrayList<>(List.of("docker", "compose"));
        command.addAll(List.of(arguments));
        try {
            Path output = Files.createTempFile("stack-test", ".txt");
            try {
                Process process = new ProcessBuilder(command)
                        .directory(ComposeStack.repositoryRoot().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    throw new IllegalStateException("`" + String.join(" ", command) + "` did not finish in 120s");
                }
                String text = Files.readString(output, StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new IllegalStateException(
                            "`" + String.join(" ", command) + "` failed with " + process.exitValue() + ":\n" + text);
                }
                return text;
            } finally {
                Files.deleteIfExists(output);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** A demo password: from the environment, else from the {@code .env} compose read it from. */
    private static String password(String variable) {
        String fromEnvironment = System.getenv(variable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        Path dotEnv = ComposeStack.repositoryRoot().resolve(".env");
        try {
            for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                if (line.startsWith(variable + "=")) {
                    return line.substring(variable.length() + 1).trim();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + dotEnv, ex);
        }
        throw new IllegalStateException(variable + " is set neither in the environment nor in " + dotEnv);
    }

    private static void print(String text) {
        System.out.print(text);
        System.out.flush();
    }
}

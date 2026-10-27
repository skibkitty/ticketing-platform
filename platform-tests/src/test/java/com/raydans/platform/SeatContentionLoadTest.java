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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Many buyers, few Seats, and the one property that matters: no Seat is sold or
 * held twice (ADR 006).
 *
 * <p>Runs against a compose stack that is already up, through the gateway's
 * flash-sale route, {@code POST /api/v1/reservations}, exactly as a client would.
 * It never starts a container: the stack's lifecycle, and the raised gateway
 * limits it needs, are in the rerun command in {@code docs/load-test.md}. Tagged
 * {@code load-test}, so it runs only under {@code -Pload-test}.
 *
 * <p>Correctness is checked twice, from two sides. Through the API, which is what
 * a Customer can see: the Reservations, and the Seats' own statuses. And in the
 * database, through {@code docker compose exec postgres psql}, which is the
 * ground truth the API could in principle misreport. Either one finding a Seat
 * in two live Reservations fails the run.
 *
 * <p>Every buyer books as the one demo Customer (id 42), because the gateway has
 * one Customer login. Separate Reservations still race for the same Seat rows,
 * so this measures seat contention, not fairness between Customers.
 *
 * <p>Settings, as system properties: {@code loadTest.baseUrl}
 * (default {@code http://localhost:8080}), {@code loadTest.buyers} (200) and
 * {@code loadTest.seats} (5). The demo passwords come from the environment, or
 * from the repository's {@code .env} when the shell has not exported them.
 */
@Tag("load-test")
class SeatContentionLoadTest {

    private static final String BASE_URL = System.getProperty("loadTest.baseUrl", "http://localhost:8080");
    private static final int BUYERS = Integer.getInteger("loadTest.buyers", 200);
    private static final int SEATS = Integer.getInteger("loadTest.seats", 5);

    /**
     * Well under the compose stack's decline threshold (50000), and a pair of
     * them too, so every payment succeeds and every winner ends {@code CONFIRMED}.
     * A decline would release Seats mid-run and make "who holds what" a moving
     * target; this test is about the race for the hold, not the saga's branches.
     */
    private static final int SEAT_PRICE_CENTS = 5000;

    private static final Set<String> LIVE = Set.of("PENDING_PAYMENT", "CONFIRMED");
    private static final Set<String> TAKEN = Set.of("HELD", "SOLD");

    private static final Duration SERVICES_READY_WITHIN = Duration.ofMinutes(3);
    private static final Duration SAGA_SETTLES_WITHIN = Duration.ofMinutes(2);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("concurrent buyers racing for the same few Seats never oversell one")
    void concurrentBuyersNeverOversellASeat() throws Exception {
        String organizer = login("organizer", password("DEMO_ORGANIZER_PASSWORD"));
        String customer = login("customer", password("DEMO_CUSTOMER_PASSWORD"));
        awaitServices(organizer, customer);

        Event event = createEvent(organizer);
        List<List<Long>> requests = seatRequests(event.seatIds());
        warmUp(customer, event.id());

        Race race = runRace(customer, event.id(), requests);
        print(race.report(event));

        // A 429 means the limiter, not the database, decided who lost: the run did
        // not measure what it is for, whatever else it shows.
        assertThat(race.count(429))
                .as("buyers refused by the gateway's limiter (429); raise RESERVATION_RATE_LIMIT,"
                        + " RESERVATION_BULKHEAD_MAX_CONCURRENT and GATEWAY_MAX_CONNECTIONS_PER_SERVICE"
                        + " for the run, as docs/load-test.md shows")
                .isZero();
        assertThat(race.count(201)).as("buyers who got a Reservation").isPositive();

        List<JsonNode> reservations = awaitSettled(customer, event.id());

        // Every 201 is a real Reservation, and nothing else made one: a 409 (or
        // anything else) that left a Reservation behind would show up here.
        Set<Long> created = race.createdReservationIds();
        assertThat(reservations.stream().map(r -> r.path("id").asLong()).collect(Collectors.toSet()))
                .as("Reservations on the Event, against the ids the 201s returned")
                .isEqualTo(created);

        Map<Long, List<Long>> holdersBySeat = new TreeMap<>();
        for (JsonNode reservation : reservations) {
            if (!LIVE.contains(reservation.path("status").asText())) {
                continue;
            }
            for (JsonNode seat : reservation.path("seats")) {
                holdersBySeat.computeIfAbsent(seat.path("id").asLong(), id -> new ArrayList<>())
                        .add(reservation.path("id").asLong());
            }
        }
        assertThat(holdersBySeat)
                .as("Seats held or bought by more than one live Reservation, as the API reports them")
                .allSatisfy((seat, holders) -> assertThat(holders).as("Seat %d", seat).hasSize(1));

        Set<Long> takenSeats = seatsOf(customer, event.id()).stream()
                .filter(seat -> TAKEN.contains(seat.path("status").asText()))
                .map(seat -> seat.path("id").asLong())
                .collect(Collectors.toSet());
        assertThat(takenSeats)
                .as("Seats HELD or SOLD, against the Seats of live Reservations")
                .isEqualTo(holdersBySeat.keySet());

        assertThat(DatabaseCheck.seatsInMoreThanOneLiveReservation(event.id()))
                .as("Seats in more than one live Reservation, as the database records them")
                .isEmpty();
    }

    // --- the race -------------------------------------------------------------

    /**
     * What each buyer asks for: alternately one Seat and an adjacent pair, round
     * the Event's Seats. Pairs overlap their neighbours, so a buyer can lose on
     * either Seat, and a Reservation is all or nothing: half a pair is never held.
     */
    private static List<List<Long>> seatRequests(List<Long> seatIds) {
        List<List<Long>> requests = new ArrayList<>();
        for (int buyer = 0; buyer < BUYERS; buyer++) {
            Long first = seatIds.get(buyer % seatIds.size());
            if (buyer % 2 == 0 || seatIds.size() == 1) {
                requests.add(List.of(first));
            } else {
                requests.add(List.of(first, seatIds.get((buyer + 1) % seatIds.size())));
            }
        }
        return requests;
    }

    /**
     * Every buyer on its own thread, held at a gate until all are ready, then
     * released at once. Virtual threads, so 200 blocked senders cost nothing on
     * the client side, and the contention is the server's.
     */
    private Race runRace(String token, long eventId, List<List<Long>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        long startedAt;
        try (ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<Long> seatIds : requests) {
                futures.add(buyers.submit(() -> {
                    HttpRequest request = authorized(token, "/api/v1/reservations")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                                    Map.of("eventId", eventId, "seatIds", seatIds))))
                            .build();
                    ready.countDown();
                    go.await();
                    long sent = System.nanoTime();
                    try {
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        return new Attempt(response.statusCode(), System.nanoTime() - sent, response.body());
                    } catch (IOException ex) {
                        // No status at all: the connection failed. Counted on its own,
                        // never folded into a status it did not get.
                        return new Attempt(-1, System.nanoTime() - sent, ex.toString());
                    }
                }));
            }
            try {
                assertThat(ready.await(30, TimeUnit.SECONDS)).as("every buyer ready at the gate").isTrue();
            } finally {
                // Opened even on a failure above, or closing the executor would
                // wait forever on buyers still parked at the gate.
                startedAt = System.nanoTime();
                go.countDown();
            }
        }
        long finishedAt = System.nanoTime();
        List<Attempt> attempts = new ArrayList<>();
        for (Future<Attempt> future : futures) {
            attempts.add(future.get());
        }
        return new Race(attempts, finishedAt - startedAt, json);
    }

    /** One buyer's request: the status it got (or -1 for none), how long it took, and the body. */
    private record Attempt(int status, long nanos, String body) {}

    private record Race(List<Attempt> attempts, long wallNanos, ObjectMapper json) {

        long count(int status) {
            return attempts.stream().filter(a -> a.status() == status).count();
        }

        Set<Long> createdReservationIds() {
            Set<Long> ids = new HashSet<>();
            for (Attempt attempt : attempts) {
                if (attempt.status() == 201) {
                    try {
                        ids.add(json.readTree(attempt.body()).path("id").asLong());
                    } catch (IOException ex) {
                        throw new UncheckedIOException("A 201 whose body is not a Reservation: " + attempt.body(), ex);
                    }
                }
            }
            return ids;
        }

        String report(Event event) {
            Map<Integer, Long> byStatus = new TreeMap<>(attempts.stream()
                    .collect(Collectors.groupingBy(Attempt::status, Collectors.counting())));
            List<Long> sorted = attempts.stream().map(Attempt::nanos).sorted().toList();
            double seconds = wallNanos / 1e9;
            StringBuilder out = new StringBuilder()
                    .append("Seat-contention load test, ").append(Instant.now()).append('\n')
                    .append("  target       ").append(BASE_URL).append(", Event ").append(event.id())
                    .append(", ").append(event.seatIds().size()).append(" Seats, ")
                    .append(attempts.size()).append(" buyers\n")
                    .append("  201 Created  ").append(byStatus.getOrDefault(201, 0L)).append('\n')
                    .append("  409 Conflict ").append(byStatus.getOrDefault(409, 0L)).append('\n')
                    .append("  429 Refused  ").append(byStatus.getOrDefault(429, 0L)).append('\n');
            byStatus.forEach((status, count) -> {
                if (status != 201 && status != 409 && status != 429) {
                    out.append("  ").append(status == -1 ? "no response" : status + "         ")
                            .append(' ').append(count).append('\n');
                }
            });
            attempts.stream()
                    .filter(a -> a.status() != 201 && a.status() != 409 && a.status() != 429)
                    .limit(3)
                    .forEach(a -> out.append("    e.g. ").append(a.status()).append(": ").append(a.body()).append('\n'));
            return out
                    .append("  wall time    ").append(String.format("%.2f s", seconds)).append('\n')
                    .append("  throughput   ").append(String.format("%.0f req/s", attempts.size() / seconds)).append('\n')
                    .append("  latency p50  ").append(millis(percentile(sorted, 50))).append('\n')
                    .append("  latency p95  ").append(millis(percentile(sorted, 95))).append('\n')
                    .append("  latency max  ").append(millis(sorted.get(sorted.size() - 1))).append('\n')
                    .append("  client       ").append(System.getProperty("os.name")).append(' ')
                    .append(System.getProperty("os.version")).append(", ")
                    .append(Runtime.getRuntime().availableProcessors()).append(" CPUs, Java ")
                    .append(Runtime.version()).append('\n')
                    .toString();
        }

        /** Nearest-rank percentile: the smallest value at least p% of the sample is at or under. */
        private static long percentile(List<Long> sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.size());
            return sorted.get(Math.max(rank, 1) - 1);
        }

        private static String millis(long nanos) {
            return String.format("%.0f ms", nanos / 1e6);
        }
    }

    // --- setting up and reading back ---------------------------------------

    private record Event(long id, List<Long> seatIds) {}

    /** A fresh Event per run, so the test reruns against a stack that has seen earlier runs. */
    private Event createEvent(String organizer) throws IOException, InterruptedException {
        List<Map<String, Object>> seats = new ArrayList<>();
        for (int number = 1; number <= SEATS; number++) {
            seats.add(Map.of("section", "LOAD", "row", "1", "seatNumber", number, "priceCents", SEAT_PRICE_CENTS));
        }
        String body = json.writeValueAsString(Map.of(
                "name", "Seat-contention load test",
                "venue", "platform-tests",
                "eventDate", Instant.now().plus(Duration.ofDays(30)).toString(),
                "seats", seats));
        JsonNode created = expect(201, authorized(organizer, "/api/v1/events")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        List<Long> seatIds = new ArrayList<>();
        created.path("seatIds").forEach(id -> seatIds.add(id.asLong()));
        return new Event(created.path("eventId").asLong(), seatIds);
    }

    /**
     * A handful of reads down the same route before the race, so the first
     * buyers do not also pay for the gateway's and the service's cold start. It
     * reads the Event's Seats rather than booking any, which would spend them.
     */
    private void warmUp(String customer, long eventId) throws IOException, InterruptedException {
        for (int i = 0; i < 20; i++) {
            seatsOf(customer, eventId);
            expect(200, authorized(customer, "/api/v1/reservations").GET().build());
        }
    }

    /**
     * The Event's Reservations once none is still waiting on its payment, so the
     * checks see the saga's result rather than a moment in it.
     */
    private List<JsonNode> awaitSettled(String customer, long eventId) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(SAGA_SETTLES_WITHIN);
        while (true) {
            List<JsonNode> onThisEvent = new ArrayList<>();
            expect(200, authorized(customer, "/api/v1/reservations").GET().build()).forEach(reservation -> {
                if (reservation.path("eventId").asLong() == eventId) {
                    onThisEvent.add(reservation);
                }
            });
            boolean pending = onThisEvent.stream().anyMatch(r -> "PENDING_PAYMENT".equals(r.path("status").asText()));
            if (!pending || Instant.now().isAfter(deadline)) {
                Map<String, Long> byStatus = onThisEvent.stream()
                        .collect(Collectors.groupingBy(r -> r.path("status").asText(), TreeMap::new, Collectors.counting()));
                print("  settled      " + byStatus + (pending ? " (gave up waiting for PENDING_PAYMENT)" : "") + '\n');
                return onThisEvent;
            }
            Thread.sleep(1000);
        }
    }

    private List<JsonNode> seatsOf(String token, long eventId) throws IOException, InterruptedException {
        List<JsonNode> seats = new ArrayList<>();
        expect(200, authorized(token, "/api/v1/events/" + eventId + "/seats").GET().build()).forEach(seats::add);
        return seats;
    }

    /**
     * The gateway listens before the services behind it are up, and a hop to one
     * still starting answers 500 (README, "The walkthrough"). Wait until the
     * route this test uses answers.
     */
    private void awaitServices(String organizer, String customer) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(SERVICES_READY_WITHIN);
        while (true) {
            int events = http.send(authorized(organizer, "/api/v1/events").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            int reservations = http.send(authorized(customer, "/api/v1/reservations").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (events == 200 && reservations == 200) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("reservation-service is not answering through the gateway after "
                        + SERVICES_READY_WITHIN + " (events " + events + ", reservations " + reservations + ")");
            }
            Thread.sleep(2000);
        }
    }

    private String login(String username, String password) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("username", username, "password", password))))
                .build();
        // A stack just started refuses or resets connections until the gateway is
        // listening, so a failed connection is retried for as long as the
        // services are given to come up.
        Instant deadline = Instant.now().plus(SERVICES_READY_WITHIN);
        HttpResponse<String> response;
        while (true) {
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException ex) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Nothing answers at " + BASE_URL
                            + "; this test runs against a compose stack that is already up (docs/load-test.md)", ex);
                }
                Thread.sleep(2000);
            }
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Logging in as " + username + " answered "
                    + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body()).path("token").asText();
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

    /**
     * A demo password: from the environment when the shell exported it, else from
     * the repository's {@code .env}, which is where {@code docker compose} read it
     * from for the stack under test.
     */
    private static String password(String variable) {
        String fromEnvironment = System.getenv(variable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        Path dotEnv = ComposeStack.repositoryRoot().resolve(".env");
        Map<String, String> values = new HashMap<>();
        if (Files.isRegularFile(dotEnv)) {
            try {
                for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    int equals = line.indexOf('=');
                    if (!line.startsWith("#") && equals > 0) {
                        values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not read " + dotEnv, ex);
            }
        }
        String fromFile = values.get(variable);
        if (fromFile == null || fromFile.isBlank()) {
            throw new IllegalStateException(variable + " is set neither in the environment nor in " + dotEnv);
        }
        return fromFile;
    }

    private static void print(String text) {
        System.out.print(text);
        System.out.flush();
    }

    /**
     * The same question asked of the database. Postgres publishes no port
     * (ADR 013), so it is asked from inside its container, which is also where a
     * human would ask it.
     */
    private static final class DatabaseCheck {

        private static final String DOUBLE_HELD = """
                SELECT rs.seat_id || ':' || string_agg(r.id::text, ',' ORDER BY r.id)
                FROM reservation.reservation_seats rs
                JOIN reservation.reservations r ON r.id = rs.reservation_id
                WHERE r.event_id = %d AND r.status IN ('PENDING_PAYMENT', 'CONFIRMED')
                GROUP BY rs.seat_id
                HAVING count(*) > 1
                """;

        /** Each line is {@code seat:reservation,reservation,...}; empty when no Seat is double-held. */
        static List<String> seatsInMoreThanOneLiveReservation(long eventId) throws IOException, InterruptedException {
            List<String> command = List.of("docker", "compose", "exec", "-T", "postgres",
                    "psql", "-U", "platform", "-d", "platform", "-tA", "-v", "ON_ERROR_STOP=1",
                    "-c", DOUBLE_HELD.formatted(eventId));
            Process process = new ProcessBuilder(command)
                    .directory(ComposeStack.repositoryRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("psql in the postgres container did not finish in 60s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("`" + String.join(" ", command.subList(0, 5)) + " psql` failed with "
                        + process.exitValue() + ":\n" + output);
            }
            return output.lines().filter(line -> !line.isBlank()).toList();
        }
    }
}

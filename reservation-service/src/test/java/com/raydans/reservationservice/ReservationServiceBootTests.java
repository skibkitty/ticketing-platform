package com.raydans.reservationservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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

    /** The port the embedded server took, so a test can reach it without going through a client. */
    @LocalServerPort
    int port;

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
        //
        // These are the shapes a compliant client can actually put on the wire, so
        // each one reaches this service's real filter in its real servlet stack and
        // has to be replaced there. That is the wiring claim this class exists to
        // make: a filter registered in `common` is really in this service's chain.
        for (String rejected : List.of(
                "trace-me-123",
                "   ",
                "x".repeat(5_000))) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(CORRELATION_HEADER, rejected);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = rest.exchange("/actuator/health", HttpMethod.GET, entity, String.class);

            String returned = response.getHeaders().getFirst(CORRELATION_HEADER);
            // Named on the assertion rather than left to the value: these differ only
            // by shape, and a failure has to say which shape stopped being replaced.
            assertThat(returned)
                    .as("%s must be replaced", described(rejected))
                    .isNotEqualTo(rejected)
                    .isNotBlank();
            // Parses, and is in the canonical form, so this is an id the platform
            // minted rather than another near-miss that happened to survive.
            assertThat(UUID.fromString(returned).toString())
                    .as("%s replaced by", described(rejected))
                    .isEqualTo(returned);
        }
    }

    @Test
    void aCorrelationIdCarryingALogForgingCharacterNeverReachesThisService() throws IOException {
        // The log-forging value, sent for real. Not through a client, because every
        // client a JVM offers validates header values and refuses this one in-process
        // — `TestRestTemplate` and `HttpURLConnection` both throw before a byte is
        // written — so a test built on either proves nothing about the filter and
        // fails on the client's validation rather than on anything this service does.
        // A socket writes the bytes as given, which is what a proxy rewriting a
        // header, or a client that is not a JVM, would put on the wire.
        //
        // The answer measured here is that the transport refuses the request: Tomcat
        // cannot parse a bare newline inside a header value, so it answers 400 and
        // the filter never runs. So the honest assertion at this level is the refusal
        // and the absence of an id — not a generated one, which is what a
        // `MockMvc` route would show, because `MockMvc` bypasses the parser that
        // stops it and hands the value straight to the filter.
        //
        // The filter's own validation is still what covers a value that *does* reach
        // it, and is asserted where that can actually happen: `CorrelationIdFilterTest`
        // for the rule itself, and `GatewayProxyBootTests` for the same rule running
        // in a real application context. This test is the service's half of the
        // boundary — that nothing a caller sends this far can put a control character
        // in this service's logs — and it holds whichever of the two defences runs.
        RawResponse response = sendRawRequestWithHeader("an-id-of-my-own\nINFO somebody did something");

        // Not "is 400": that is Tomcat's spelling of a malformed request. The
        // property is that the request was not served, and that the caller is told
        // no id for it — an id here would be the one value that must never be
        // minted for a forged header, since it is what ends up in every log line.
        assertThat(response.status()).isNotIn(IntStream.rangeClosed(200, 299).boxed().toList());
        assertThat(response.headers().toLowerCase(Locale.ROOT))
                .as("no correlation id is issued for a value the transport refused")
                .doesNotContain(CORRELATION_HEADER.toLowerCase(Locale.ROOT));
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

    /** Writes a request line and headers to the socket itself, so nothing validates the value first. */
    private RawResponse sendRawRequestWithHeader(String correlationId) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET /actuator/health HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + CORRELATION_HEADER + ": " + correlationId + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            // Read to the end rather than to the blank line, so the assertion is about
            // every header the service sent and not only the ones before the body.
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int statusLineEnd = response.indexOf("\r\n");
            String statusLine = statusLineEnd < 0 ? response : response.substring(0, statusLineEnd);

            Matcher status = Pattern.compile("HTTP/\\d\\.\\d (\\d{3})").matcher(statusLine);
            assertThat(status.find()).as("a status line to read: %s", statusLine).isTrue();
            return new RawResponse(Integer.parseInt(status.group(1)), response);
        }
    }

    /** How a rejected value is named on an assertion, so a failure says which shape it was. */
    private static String described(String rejected) {
        if (rejected.isBlank()) {
            return "a blank value";
        }
        return rejected.length() > 40 ? "an oversized value" : '"' + rejected + '"';
    }

    /** What came back off the socket, for a request no client was willing to send. */
    private record RawResponse(int status, String headers) {
    }
}
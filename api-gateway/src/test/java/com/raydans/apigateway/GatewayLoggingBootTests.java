package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.raydans.common.web.CorrelationIdFilter;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the gateway writes to its log, read back the way an operator reads it:
 * from the console, one JSON object per line, by correlation id (#14).
 *
 * <p>The console is captured rather than a logger's events, because the claim is
 * about the output's shape — an appender sees an event whether or not anything
 * would ever render it as JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GatewayLoggingBootTests {

    private static final String A_CALLERS_CORRELATION_ID = "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static StandInService reservationService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @BeforeAll
    static void startTheStandInService() throws IOException {
        reservationService = StandInService.start();
    }

    @AfterAll
    static void stopTheStandInService() {
        reservationService.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("RESERVATION_SERVICE_URL", reservationService::url);
        registry.add("JWT_SECRET", () -> "a-signing-key-this-test-only-ever-signs-with");
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @BeforeEach
    void aHealthyService() {
        reservationService.reset();
    }

    @Test
    void aProxiedRequestIsLoggedAsJsonUnderItsCorrelationId(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(42L, Role.CUSTOMER))
                        .header(CorrelationIdFilter.HEADER_NAME, A_CALLERS_CORRELATION_ID))
                .andExpect(status().isOk());

        assertThat(linesCarrying(output, A_CALLERS_CORRELATION_ID))
                .as("the gateway's hop is greppable by the id the caller was given")
                .anySatisfy(line -> {
                    assertThat(line.path("level").asText()).isEqualTo("INFO");
                    assertThat(line.path("message").asText()).contains("GET /api/v1/events").contains("200");
                });
    }

    @Test
    void aRefusedRequestIsLoggedUnderItsCorrelationIdToo(CapturedOutput output) throws Exception {
        // The request auth turns away is the one most worth finding afterwards, and
        // the reason the correlation filter runs ahead of auth at all.
        String correlationId = mvc.perform(get("/api/v1/events"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);

        assertThat(linesCarrying(output, correlationId))
                .anySatisfy(line -> assertThat(line.path("message").asText())
                        .contains("GET /api/v1/events").contains("401"));
    }

    @Test
    void everyLineTheGatewayWritesIsOneJsonObject(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/events").header(HttpHeaders.AUTHORIZATION, bearer(42L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(output.getOut().lines().filter(line -> !line.isBlank()).toList())
                .isNotEmpty()
                .allSatisfy(line -> {
                    JsonNode parsed = parse(line);
                    assertThat(parsed != null && parsed.isObject()).as("not a JSON object: %s", line).isTrue();
                });
    }

    @Test
    void aReplacedCorrelationIdIsRecordedWithoutTheCallersValue(CapturedOutput output) throws Exception {
        String forged = "abc\nINFO admin authenticated";

        String minted = mvc.perform(get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(42L, Role.CUSTOMER))
                        .header(CorrelationIdFilter.HEADER_NAME, forged))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);

        // Visible to the operator under the id the caller was actually given, so an
        // integration asking "why is my id not in your logs" has an answer to find.
        assertThat(linesCarrying(output, minted))
                .anySatisfy(line -> assertThat(line.path("message").asText()).contains("Replaced"));
        assertThat(output.getAll()).doesNotContain("admin authenticated");
    }

    private String bearer(long customerId, Role role) {
        return "Bearer " + tokens.issue(customerId, Set.of(role)).token();
    }

    /** The captured lines whose top-level {@code correlationId} is {@code correlationId}. */
    private static List<JsonNode> linesCarrying(CapturedOutput output, String correlationId) {
        assertThat(correlationId).isNotBlank();
        return output.getOut().lines()
                .map(GatewayLoggingBootTests::parse)
                .filter(line -> line != null && correlationId.equals(line.path(CorrelationIdFilter.MDC_KEY).asText()))
                .toList();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }
}

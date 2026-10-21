package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A payment-service that is not there at all: the connection is refused (#13).
 * A class of its own because the route has to point at a port nothing listens
 * on, for the whole context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentServiceUnreachableBootTests {

    private static final int CALLS_TO_JUDGE = 2;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @Autowired
    CircuitBreakerRegistry circuits;

    @DynamicPropertySource
    static void aPaymentServiceThatIsNotRunning(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + closedPort);
        registry.add("PAYMENTS_CIRCUIT_WINDOW_SIZE", () -> CALLS_TO_JUDGE);
        registry.add("PAYMENTS_CIRCUIT_MIN_CALLS", () -> CALLS_TO_JUDGE);
        registry.add("JWT_SECRET", () -> "a-signing-key-this-test-only-ever-signs-with");
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @Test
    void aRefusedConnectionCountsAsAFailureAndOpensTheCircuit() throws Exception {
        String bearer = "Bearer " + tokens.issue(42L, Set.of(Role.CUSTOMER)).token();
        for (int i = 0; i < CALLS_TO_JUDGE; i++) {
            mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isInternalServerError())
                    // Nothing about the refused address reaches the caller.
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        }

        assertThat(circuits.circuitBreaker("payments").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isServiceUnavailable());
    }
}

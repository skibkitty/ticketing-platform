package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.apigateway.auth.JwtService;
import com.raydans.apigateway.auth.Role;
import com.raydans.apigateway.config.CustomerIdHeaderFilter;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The identity half of ADR 016 on the payment route: whatever {@code X-Customer-Id}
 * a client sends, payment-service is only ever handed the one the gateway derived
 * from the verified token. The enforcement half — a Customer is 404 on someone
 * else's reservation — is {@code PaymentFlowBootTests} in payment-service.
 * Neither half is sufficient alone (ADR 014).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIdentityBootTests {

    private static final long CUSTOMER_ID = 42L;
    private static final long ORGANIZER_ID = 43L;
    private static final long ADMIN_ID = 44L;

    private static StandInService paymentService;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService tokens;

    @BeforeAll
    static void startTheStandInService() throws IOException {
        paymentService = StandInService.start();
    }

    @AfterAll
    static void stopTheStandInService() {
        paymentService.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("PAYMENT_SERVICE_URL", paymentService::url);
        registry.add("JWT_SECRET", () -> "a-signing-key-this-test-only-ever-signs-with");
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    @BeforeEach
    void aHealthyService() {
        paymentService.reset();
    }

    @Test
    void aCustomersOwnIdIsWhatThePaymentReadIsScopedBy() throws Exception {
        mvc.perform(get("/api/v1/payments/7").header(HttpHeaders.AUTHORIZATION, bearer(CUSTOMER_ID, Role.CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(customerIdsReceived()).containsExactly(List.of("42"));
    }

    @Test
    void aCustomerIdForgedOnAPaymentReadIsReplacedByTheOneInTheToken() throws Exception {
        // The impersonation attempt: Customer 42 asking as Customer 999.
        mvc.perform(get("/api/v1/payments/7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(CUSTOMER_ID, Role.CUSTOMER))
                        .header(CustomerIdHeaderFilter.HEADER_NAME, "999"))
                .andExpect(status().isOk());

        assertThat(customerIdsReceived()).containsExactly(List.of("42"));
    }

    @Test
    void aCustomerIdForgedTwiceOnAPaymentReadDoesNotReachTheServiceTwice() throws Exception {
        // Two values on one header: a downstream reading only the first would
        // believe the forgery if either survived.
        mvc.perform(get("/api/v1/payments/7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(CUSTOMER_ID, Role.CUSTOMER))
                        .header(CustomerIdHeaderFilter.HEADER_NAME, "999", "1"))
                .andExpect(status().isOk());

        assertThat(customerIdsReceived()).containsExactly(List.of("42"));
    }

    @Test
    void anOrganizerWithAForgedCustomerIdIsRefusedBeforeTheService() throws Exception {
        mvc.perform(get("/api/v1/payments/7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ORGANIZER_ID, Role.ORGANIZER))
                        .header(CustomerIdHeaderFilter.HEADER_NAME, "42"))
                .andExpect(status().isForbidden());

        assertThat(paymentService.received()).isEmpty();
    }

    @Test
    void anAdminWithAForgedCustomerIdIsRefusedBeforeTheService() throws Exception {
        // No operator route for payments exists (ADR 016), so ADMIN has no way in.
        mvc.perform(get("/api/v1/payments/7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, Role.ADMIN))
                        .header(CustomerIdHeaderFilter.HEADER_NAME, "42"))
                .andExpect(status().isForbidden());

        assertThat(paymentService.received()).isEmpty();
    }

    @Test
    void aForgedCustomerIdWithNoTokenForwardsNothing() throws Exception {
        mvc.perform(get("/api/v1/payments/7").header(CustomerIdHeaderFilter.HEADER_NAME, "42"))
                .andExpect(status().isUnauthorized());

        assertThat(paymentService.received()).isEmpty();
    }

    /** Every value of {@code X-Customer-Id} on each request that reached the service. */
    private List<List<String>> customerIdsReceived() {
        return paymentService.receivedHeaders().stream()
                .map(headers -> {
                    List<String> values = headers.get(CustomerIdHeaderFilter.HEADER_NAME);
                    return values == null ? List.<String>of() : List.copyOf(values);
                })
                .toList();
    }

    private String bearer(long callerId, Role role) {
        return "Bearer " + tokens.issue(callerId, Set.of(role)).token();
    }
}

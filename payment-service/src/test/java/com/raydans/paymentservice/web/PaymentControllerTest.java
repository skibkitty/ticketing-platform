package com.raydans.paymentservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.paymentservice.payment.InvalidPaymentLookupException;
import com.raydans.paymentservice.payment.PaymentNotFoundException;
import com.raydans.paymentservice.payment.PaymentQueryService;
import com.raydans.paymentservice.payment.PaymentResponse;
import com.raydans.paymentservice.payment.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentControllerTest {

    PaymentQueryService payments = mock(PaymentQueryService.class);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The same ObjectMapper shape Spring Boot builds, so the asserted wire format
        // (ISO-8601 instants) is the one a real client actually receives.
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mvc = MockMvcBuilders
                .standaloneSetup(new PaymentController(payments))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aCustomerReadsThePaymentBehindTheirReservation() throws Exception {
        when(payments.get(101L, 99L)).thenReturn(new PaymentResponse(
                7L,
                101L,
                44000,
                PaymentStatus.SUCCEEDED,
                Instant.parse("2026-10-21T10:15:30Z"),
                Instant.parse("2026-10-21T10:15:31Z")));

        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.reservationId").value(101))
                .andExpect(jsonPath("$.amountCents").value(44000))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T10:15:30Z"))
                .andExpect(jsonPath("$.settledAt").value("2026-10-21T10:15:31Z"));
    }

    @Test
    void aDeclinedPaymentIsReadLikeAnyOther() throws Exception {
        when(payments.get(102L, 99L)).thenReturn(new PaymentResponse(
                8L, 102L, 50000, PaymentStatus.FAILED, Instant.parse("2026-10-21T10:15:30Z"), null));

        mvc.perform(get("/api/v1/payments/102").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void anUnknownReservationIs404InTheSharedErrorShape() throws Exception {
        when(payments.get(404L, 99L))
                .thenThrow(new PaymentNotFoundException("internal detail: lookup for reservation 404 missed"));

        mvc.perform(get("/api/v1/payments/404").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                // The fixed text, not the exception's: the detail stays in the logs.
                .andExpect(jsonPath("$.message").value("Payment not found"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/404"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void theCallerFromTheHeaderIsWhoTheLookupIsScopedTo() throws Exception {
        // Customer 98 asking for customer 99's reservation: the service is asked on 98's
        // behalf and answers as it would for an unissued id (ADR 016).
        when(payments.get(101L, 98L))
                .thenThrow(new PaymentNotFoundException("Payment for reservation 101 was not found"));

        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "98"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found"));
    }

    @Test
    void aLookupTheServiceRefusesIs400WithItsClientMessage() throws Exception {
        when(payments.get(0L, 99L)).thenThrow(new InvalidPaymentLookupException("reservationId must be a positive id"));
        when(payments.get(-5L, 99L)).thenThrow(new InvalidPaymentLookupException("reservationId must be a positive id"));
        when(payments.get(101L, 0L)).thenThrow(new InvalidPaymentLookupException("X-Customer-Id must be a positive id"));

        mvc.perform(get("/api/v1/payments/0").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reservationId must be a positive id"));
        mvc.perform(get("/api/v1/payments/-5").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reservationId must be a positive id"));
        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Customer-Id must be a positive id"));
    }

    @Test
    void anUnexpectedFailureIsAGeneric500ThatSaysNothingAboutWhy() throws Exception {
        when(payments.get(101L, 99L)).thenThrow(new IllegalStateException(
                "could not open connection to jdbc:postgresql://postgres:5432/platform"));

        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("postgres"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void aRequestThatDidNotComeThroughTheGatewayIsRefusedWithoutALookup() throws Exception {
        mvc.perform(get("/api/v1/payments/101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required request header 'X-Customer-Id' is not present"));

        verifyNoInteractions(payments);
    }

    @Test
    void aNonNumericReservationIdIs400NotA500() throws Exception {
        mvc.perform(get("/api/v1/payments/abc").header(PaymentController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'reservationId'"))
                .andExpect(content().string(not(containsString("NumberFormatException"))));

        verifyNoInteractions(payments);
    }

    @Test
    void aNonNumericCustomerHeaderIs400NotA500() throws Exception {
        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "someone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'X-Customer-Id'"));
        // Past the range of a long is the same malformed header, not a server fault.
        mvc.perform(get("/api/v1/payments/101").header(PaymentController.CUSTOMER_HEADER, "99999999999999999999"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(payments);
    }
}

package com.raydans.paymentservice.web;

import com.raydans.common.web.ApiErrorResponse;
import com.raydans.paymentservice.payment.InvalidPaymentLookupException;
import com.raydans.paymentservice.payment.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every payment-service failure to the shared {@code ApiErrorResponse} error contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * One fixed message for every absent Payment, whatever the exception says.
     * Someone else's reservation and an unissued id must produce the same body
     * (ADR 016), and a constant cannot drift apart for the two cases the way a
     * message built from the lookup could.
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(PaymentNotFoundException ex, HttpServletRequest request) {
        log.debug("{} on {}", ex.getMessage(), request.getRequestURI());
        HttpStatus status = HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).body(error(status, "Payment not found", request));
    }

    @ExceptionHandler(InvalidPaymentLookupException.class)
    ResponseEntity<ApiErrorResponse> invalidLookup(InvalidPaymentLookupException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(error(status, ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        // Names the parameter but does not echo the value back: the caller already has
        // it, and the converter's own text describes this service's types.
        String message = "Invalid value for '" + ex.getName() + "'";
        return ResponseEntity.status(status).body(error(status, message, request));
    }

    /**
     * A missing {@code X-Customer-Id}: a request that never came through the gateway.
     * Answered 400 rather than left to the catch-all, which would report a server fault
     * for a caller's missing proof (ADR 011).
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> missingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Required request header '" + ex.getHeaderName() + "' is not present";
        return ResponseEntity.status(status).body(error(status, message, request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> fallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(error(status, "An unexpected error occurred", request));
    }

    private ApiErrorResponse error(HttpStatus status, String message, HttpServletRequest request) {
        return new ApiErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), List.of());
    }
}

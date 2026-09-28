package com.raydans.notificationservice.web;

import com.raydans.common.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every notification-service failure to the shared {@code ApiErrorResponse} error contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Input the service refused: a non-positive {@code customerId} on the read surface, or a
     * terminal event payload that does not fit its event type.
     *
     * <p>Handled explicitly, not left to the catch-all: only the inbound half of
     * {@link IllegalArgumentException} is a bad request — the Kafka side rethrows it to
     * trigger a retry and eventual dead-lettering (ADR 008) and never reaches this advice —
     * but on the HTTP side it only ever means the caller sent something invalid, and letting
     * the catch-all answer 500 would blame the server for a request it could have rejected.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidArgument(IllegalArgumentException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Invalid value for '" + ex.getName() + "': '" + ex.getValue() + "'";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Required request parameter '" + ex.getParameterName() + "' is not present";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    /**
     * A missing {@code X-Customer-Id} on the self-service read, which is the
     * request that arrives with nothing proven about who is asking (ADR 011).
     *
     * <p>Its own case rather than a fold-in with the parameter above because they are
     * not the same failure and only one of them is an attack: no parameter is a
     * malformed request, while no header is a request that never came through the
     * gateway. Left unhandled it fell to the catch-all below and was answered 500,
     * which reports a server fault for a caller's missing proof and would have
     * buried the isolation property in a monitoring dashboard.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> missingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Required request header '" + ex.getHeaderName() + "' is not present";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> fallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "An unexpected error occurred";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    private ApiErrorResponse error(
            HttpStatus status, String error, String message, HttpServletRequest request, List<String> details) {
        return new ApiErrorResponse(
                Instant.now(), status.value(), error, message, request.getRequestURI(), details);
    }
}

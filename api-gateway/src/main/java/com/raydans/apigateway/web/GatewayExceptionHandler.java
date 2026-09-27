package com.raydans.apigateway.web;

import com.raydans.apigateway.auth.InvalidCredentialsException;
import com.raydans.common.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the gateway's own failures to the shared {@code ApiErrorResponse} shape,
 * so a caller sees the same error body whether the gateway or a service
 * produced it.
 *
 * <p>Only the login surface is covered. Everything the auth filter refuses is
 * answered by {@link ErrorResponseWriter} before a controller exists, and this
 * advice has no way to see those — which is why the two share a shape rather
 * than this one covering the filter's cases.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /**
     * A login the gateway will not issue a token for.
     *
     * <p>401 rather than 403: the caller did not authenticate, which is a
     * different thing from authenticating and then being refused, and a client
     * that retries a 403 with the same credentials will keep failing.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, List.of());
    }

    /** A login body that is not JSON, or not the JSON this endpoint takes. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable login body on {}", request.getRequestURI(), ex);
        return respond(
                HttpStatus.BAD_REQUEST,
                "Request body must be {\"username\": \"...\", \"password\": \"...\"}",
                request,
                List.of());
    }

    /**
     * A login missing its username or password.
     *
     * <p>Reported per field, unlike {@link InvalidCredentialsException}, because
     * there is nothing to protect: a caller who sent no username is not
     * discovering whether that username exists.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidLogin(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "Request body is not valid", request, details);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> fallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> respond(
            HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details));
    }
}

package com.raydans.apigateway.web;

import com.raydans.apigateway.auth.InvalidCredentialsException;
import com.raydans.apigateway.auth.ThrottledLoginException;
import com.raydans.common.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps the login surface's failures to the shared {@code ApiErrorResponse}
 * shape. Everything the auth filter refuses is answered by
 * {@link ErrorResponseWriter} before a controller exists, and this advice has no
 * way to see those.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ErrorResponseWriter bodies;

    public GatewayExceptionHandler(ErrorResponseWriter bodies) {
        this.bodies = bodies;
    }

    /**
     * A login the gateway will not issue a token for. 401 rather than 403: the
     * caller did not authenticate, which is a different thing from
     * authenticating and then being refused, and a client that retries a 403 with
     * the same credentials will keep failing.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, List.of());
    }

    /**
     * A login the gateway has stopped answering guesses for.
     *
     * <p>429 rather than 401, and deliberately carrying nothing else: a limiter
     * that said "slow down, you have been guessing" would tell a caller it has
     * been guessing, and a caller that has been refused here is a caller who is
     * being told nothing at all. {@code Retry-After} goes on because it is the
     * one thing a well-behaved client cannot work out unaided — how long — and it
     * discloses nothing about any credential.
     *
     * <p>Built as a {@code ResponseEntity} rather than through
     * {@link #respond} because that helper takes no headers, and the header is
     * not decoration: without it a client cannot tell a rate limit from a
     * credential problem and will retry a password it has not changed.
     */
    @ExceptionHandler(ThrottledLoginException.class)
    ResponseEntity<ApiErrorResponse> loginThrottled(
            ThrottledLoginException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(bodies.body(
                        request, HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), List.of()));
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
     * A login missing its username or password. Reported per field, unlike
     * {@link InvalidCredentialsException}, because there is nothing to protect:
     * a caller who sent no username is not discovering whether it exists.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidLogin(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "Request body is not valid", request, details);
    }

    /**
     * A path no route matched. Spring MVC's resource handler raises this when the
     * gateway's routing declines a request, and answers it as a 404 by default —
     * but only when nothing else handles it first, and the catch-all below
     * catches everything. So without this, every unrouted path is reported as an
     * unexpected server error: a wrong URL answers 500 with an ERROR log line and
     * tells a caller the gateway is broken when it is only being asked for
     * something it does not have.
     *
     * <p>This is how the gateway answers an operator path that belongs to no
     * service, which is the point of the narrow notification route (ADR 011): the
     * path does not exist here, rather than existing on a service that would
     * 404 it. A route that matched the caller's ADMIN role but no data would be a
     * different answer, and one that arrives with the caller's identity headers on
     * it — which is the reason this is 404 here and not a proxy to a service's own
     * 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> noRouteMatches(
            NoResourceFoundException ex, HttpServletRequest request) {
        // Deliberately not logged: an unrouted path is a client's question, not the
        // gateway's malfunction, and logging it at error level per request would be
        // noise an operator learns to ignore.
        return respond(HttpStatus.NOT_FOUND, "No such route", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> fallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> respond(
            HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        return ResponseEntity.status(status).body(bodies.body(request, status, message, details));
    }
}

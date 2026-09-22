package com.raydans.reservationservice.web;

import com.raydans.common.web.ApiErrorResponse;
import com.raydans.reservationservice.event.DuplicateSeatException;
import com.raydans.reservationservice.event.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every reservation-service failure to the shared {@code ApiErrorResponse} error contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SeatUnavailableException.class)
    ResponseEntity<ApiErrorResponse> seatUnavailable(SeatUnavailableException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(error(status, ex, request));
    }

    @ExceptionHandler(DuplicateSeatException.class)
    ResponseEntity<ApiErrorResponse> duplicateSeat(DuplicateSeatException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(error(status, ex, request));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).body(error(status, ex, request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), ex.getMessage(), request, details));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Invalid value for '" + ex.getName() + "': '" + ex.getValue() + "'";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed request body on {} {}", request.getMethod(), request.getRequestURI(), ex);
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Malformed request body";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Required request parameter '" + ex.getParameterName() + "' is not present";
        return ResponseEntity.status(status).body(error(status, status.getReasonPhrase(), message, request, List.of()));
    }

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

    private ApiErrorResponse error(HttpStatus status, Exception ex, HttpServletRequest request) {
        return error(status, status.getReasonPhrase(), ex.getMessage(), request, List.of());
    }

    private ApiErrorResponse error(
            HttpStatus status, String error, String message, HttpServletRequest request, List<String> details) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                details);
    }
}
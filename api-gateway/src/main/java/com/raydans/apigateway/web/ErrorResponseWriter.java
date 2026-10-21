package com.raydans.apigateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.web.ApiErrorResponse;
import com.raydans.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the shared {@code ApiErrorResponse} shape for failures raised in a
 * servlet filter, before any {@code @RestControllerAdvice} can see them: a
 * rejected reservation never reaches a controller, so the advice is not on the
 * path and something has to write the body. The advice builds its bodies here
 * too, so a caller cannot tell which half of the gateway refused them.
 */
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Answers the request with an {@code ApiErrorResponse} and stops.
     *
     * <p>Does nothing if the response is already committed, so a failure raised
     * after the proxied service has begun responding cannot append an error body
     * to a half-written response.
     */
    public void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        write(request, response, status, message, HttpHeaders.EMPTY);
    }

    /**
     * As {@link #write(HttpServletRequest, HttpServletResponse, HttpStatus, String)},
     * with headers that belong to this refusal, such as {@code Retry-After}. They
     * are set before the body, because writing the body commits the response.
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String message,
            HttpHeaders headers)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // reset() clears headers as well as the body, and the correlation id was
        // put on the response before anything could refuse it. Without carrying
        // it over, a refused request would be the one response with no id to grep
        // its logs by, which is what CorrelationIdFilter runs first to prevent.
        String correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        response.reset();
        if (correlationId != null) {
            response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body(request, status, message, List.of()));
    }

    public ApiErrorResponse body(
            HttpServletRequest request, HttpStatus status, String message, List<String> details) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details);
    }
}

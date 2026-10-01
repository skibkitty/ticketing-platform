package com.raydans.apigateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        if (response.isCommitted()) {
            return;
        }
        response.reset();
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

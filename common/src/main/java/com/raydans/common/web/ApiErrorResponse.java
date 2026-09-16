package com.raydans.common.web;

import java.time.Instant;
import java.util.List;

/**
 * The one error shape every service returns for every non-2xx response.
 * Mirror of the spec's {@code ApiErrorResponse {timestamp, status, error,
 * message, path, details}} contract.
 *
 * @param timestamp when the error occurred
 * @param status    the HTTP status code
 * @param error     the HTTP reason phrase (e.g. {@code Conflict})
 * @param message   a human-readable explanation
 * @param path      the request path that failed
 * @param details   field-level or fallback explanations
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details) {}
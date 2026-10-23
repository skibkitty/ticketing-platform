package com.raydans.apigateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request the gateway answers: method, path, status and how long it
 * took, under the request's correlation id (#14).
 *
 * <p>The first line of every request's story, and the only one for a request the
 * gateway refuses, so it wraps auth and resilience rather than sitting behind
 * them. It logs the path and not the query string, because a query string is the
 * one part of a URL a client puts its own data in.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean returned = false;
        try {
            filterChain.doFilter(request, response);
            returned = true;
        } finally {
            log.info("{} {} -> {} in {} ms",
                    request.getMethod(),
                    request.getRequestURI(),
                    statusSent(response, returned),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /**
     * The status the caller gets. An exception that gets past every handler is
     * turned into a 500 by the container after this filter has returned, so the
     * response's own status, still untouched, would be logged as a 200 that was
     * never sent. Unless the response is already committed: then the status has
     * gone out and the container can no longer change it.
     */
    private static int statusSent(HttpServletResponse response, boolean returned) {
        if (!returned && !response.isCommitted()) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        return response.getStatus();
    }
}

package com.raydans.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Carries a request-scoped correlation id end-to-end. Echoes an inbound
 * {@value #HEADER_NAME} header when it is a value this platform recognises, and
 * generates a UUID when it is not. The id is exposed to downstream logs via the
 * SLF4J MDC key {@value #MDC_KEY} for the duration of the request and echoed back
 * on the response so a caller can follow the request through every service's
 * logs.
 *
 * <p>Echoing a caller's value is what makes the id useful across services, and
 * it is also a hole in every log line the platform will ever write about the
 * request. The value is not quoted or escaped on its way into the MDC, so a
 * caller who controls it controls a field of every downstream log entry — a
 * newline in the value is a forged log line, and a terminal escape sequence is
 * a forged line in a log viewer. Neither is an authentication bypass, and
 * neither is cosmetic in a system whose whole debugging story is "grep the
 * correlation id".
 *
 * <p>So the value has to be one this platform would have generated, or one in the
 * shape it propagates: a canonical {@link UUID}, or a 32-character hexadecimal
 * trace id as W3C trace context and OpenTelemetry both spell one. Those are the
 * two forms a caller can already be holding, so accepting them costs a real
 * integration nothing, and a caller that wants an id of its own can mint a UUID
 * in the time it takes to read this. Anything else is replaced rather than
 * rejected, because the id is a debugging affordance and refusing the request
 * over it would turn a diagnostic into an outage: the caller still gets a
 * response, the response still carries the effective id, and the caller's logs
 * and the platform's agree because the gateway overwrites the header from the
 * MDC before forwarding (ADR 002).
 *
 * <p>A rejected value is not logged. Writing what a caller sent to a log is how a
 * log becomes an injection target, so a replacement is not recorded as the
 * attacker's own text. It is also not yet recorded at all: this module has no
 * logger, and where a replacement should be *visible* — the operator wanting to
 * know why an integration's id stopped being the one in its own logs — is a
 * question about the logging shape that
 * {@code #14} settles rather than one this filter should answer on its own. Until
 * then the only way to see that a value was replaced is the response header,
 * which is the honest minimum: the caller learns the effective id, and the
 * platform's logs and the caller's agree on it.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * The canonical form {@link UUID#randomUUID()} produces: 32 hex digits in five
     * groups, and the only one accepted with dashes. Parsed with
     * {@link UUID#fromString} rather than by regex, because Java 11+ that is
     * itself the length and the character class — a pattern here would be a second
     * spelling of the same rule to keep in step.
     */
    private static final int UUID_STRING_LENGTH = 36;

    /**
     * A W3C {@code traceparent} or OpenTelemetry trace id with the dashes left
     * off: 32 hex characters, which is the other id shape a caller plausibly
     * already holds. Distinct from {@link #UUID_STRING_LENGTH} by length, so the
     * two rules cannot both match and neither shadows the other.
     */
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (!isAcceptable(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Whether this platform is willing to put a caller's value in every log line
     * of every service this request touches.
     *
     * <p>Length is checked before {@link UUID#fromString} because that is what
     * keeps a large value from being parsed at all, and a header a client can make
     * arbitrarily large is a cost worth declining before paying.
     */
    private static boolean isAcceptable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > UUID_STRING_LENGTH) {
            return false;
        }
        if (value.length() == UUID_STRING_LENGTH) {
            return isCanonicalUuid(value);
        }
        return TRACE_ID.matcher(value).matches();
    }

    /** Whether the value is exactly what {@link UUID#toString()} would have produced. */
    private static boolean isCanonicalUuid(String value) {
        try {
            // fromString is lenient — it accepts a short group and zero-pads it —
            // so the round trip is the check: a value that does not come back out
            // unchanged was not in the canonical form and is not what a UUID here
            // would ever have generated.
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}

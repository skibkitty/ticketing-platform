package com.raydans.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /** ESC written as a value rather than typed into this file, so the source stays greppable. */
    private static final String ESCAPE = String.valueOf((char) 0x1B);

    /** A value a caller can hold, and that this platform is willing to propagate. */
    private static final String A_CALLERS_UUID = "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b";

    /** What the filter answers with when it will not pass a caller's value on. */
    private static void assertItIsAGeneratedUuid(String correlationId) {
        assertThat(correlationId)
                .isNotBlank()
                .satisfies(generated -> assertThat(UUID.fromString(generated)).isNotNull())
                .satisfies(generated -> assertThat(UUID.fromString(generated).toString()).isEqualTo(generated));
    }

    @Test
    void echoesACallersUuid() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, A_CALLERS_UUID);

        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(A_CALLERS_UUID);
        assertThat(mdcDuringRequest.get()).isEqualTo(A_CALLERS_UUID);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void echoesAThirtyTwoCharacterTraceId() throws Exception {
        // The other id a caller can already be holding: a W3C traceparent or
        // an OpenTelemetry trace id with the dashes stripped. Accepting it is what
        // keeps this from being a change an existing integration has to notice.
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest traced = new MockHttpServletRequest();
        MockHttpServletResponse tracedResponse = new MockHttpServletResponse();
        traced.addHeader(CorrelationIdFilter.HEADER_NAME, traceId);

        filter.doFilter(traced, tracedResponse, new MockFilterChain());

        assertThat(tracedResponse.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(traceId);
    }

    @Test
    void replacesTheAllZeroTraceId() throws Exception {
        // 32 hex characters, so the trace-id rule matches it — and W3C Trace
        // Context reserves exactly this value to mean "no valid trace id". It is
        // the one value in this shape a caller cannot be holding, because a span
        // outside a trace reports it rather than inventing a real one.
        //
        // Propagating it would be worse than propagating a hostile value: that one
        // is visibly wrong, while this one is indistinguishable from a real id and
        // is the same for every caller, so every request in the platform would be
        // reported under one id and none of them could be told apart.
        String allZero = "0".repeat(32);
        MockHttpServletRequest zeroed = new MockHttpServletRequest();
        MockHttpServletResponse zeroedResponse = new MockHttpServletResponse();
        zeroed.addHeader(CorrelationIdFilter.HEADER_NAME, allZero);

        filter.doFilter(zeroed, zeroedResponse, new MockFilterChain());

        assertItIsAGeneratedUuid(zeroedResponse.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertThat(zeroedResponse.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo(allZero);
    }

    @Test
    void aTraceIdThatIsNearlyAllZerosIsStillPropagated() throws Exception {
        // The other side of that rule, and the reason it is written as one value
        // excluded by name rather than as a pattern over the hex digits: a real
        // trace id can begin with a long run of zeros, and rejecting anything that
        // looks like them would refuse an id a caller genuinely holds — which is
        // the cost the two accepted id shapes are chosen to avoid. Both of these
        // differ from the all-zero id in the last character alone, and both are
        // propagated.
        for (String nearlyAllZero : List.of(
                "0".repeat(31) + "1",
                "0".repeat(31) + "a")) {
            MockHttpServletRequest nearly = new MockHttpServletRequest();
            MockHttpServletResponse nearlyResponse = new MockHttpServletResponse();
            nearly.addHeader(CorrelationIdFilter.HEADER_NAME, nearlyAllZero);

            filter.doFilter(nearly, nearlyResponse, new MockFilterChain());

            assertThat(nearlyResponse.getHeader(CorrelationIdFilter.HEADER_NAME))
                    .as("%s is a trace id, not the reserved all-zero one", nearlyAllZero)
                    .isEqualTo(nearlyAllZero);
        }
    }


    @Test
    void generatesUuidWhenAbsent() throws Exception {
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };

        filter.doFilter(request, response, chain);

        assertItIsAGeneratedUuid(response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertThat(mdcDuringRequest.get()).isEqualTo(response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void treatsBlankHeaderAsAbsent() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");

        filter.doFilter(request, response, new MockFilterChain());

        assertItIsAGeneratedUuid(response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void replacesAnOversizedValueRatherThanPropagatingIt() throws Exception {
        // A client can make this header as long as it likes, and the value goes
        // into every downstream service's log line. Bounded by the length of the
        // longest id this platform accepts, so there is no limit here chosen to
        // suit a particular log formatter — a longer one is a new format to
        // agree, not a number to raise.
        String oversized = "a".repeat(100_000);
        request.addHeader(CorrelationIdFilter.HEADER_NAME, oversized);

        filter.doFilter(request, response, new MockFilterChain());

        assertItIsAGeneratedUuid(response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo(oversized);
    }

    @Test
    void replacesAValueThatIsNotAnIdAtAll() throws Exception {
        // The ordinary case this exists for. Replaced rather than refused: the id
        // is a debugging affordance, so the caller still gets its response and
        // still gets an id to quote in it, and the gateway overwrites the header
        // from the MDC before forwarding, so every service agrees on which id the
        // request is travelling under.
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "trace-me-123");

        filter.doFilter(request, response, new MockFilterChain());

        assertItIsAGeneratedUuid(response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void replacesAValueCarryingALogForgingControlCharacter() throws Exception {
        // A newline is a forged log line in every service downstream, and an
        // escape sequence is a forged line in a log viewer. The value is not
        // quoted or escaped on its way into the MDC, so it never gets the chance.
        for (String forged : List.of(
                "abc\nINFO admin authenticated",
                "abc\r\nX-Injected: yes",
                "abc" + ESCAPE + "]8;;http://evil",
                "abc" + (char) 0x00 + "def")) {
            MockHttpServletRequest injecting = new MockHttpServletRequest();
            MockHttpServletResponse injected = new MockHttpServletResponse();
            injecting.addHeader(CorrelationIdFilter.HEADER_NAME, forged);

            filter.doFilter(injecting, injected, new MockFilterChain());

            assertItIsAGeneratedUuid(injected.getHeader(CorrelationIdFilter.HEADER_NAME));
            assertThat(injected.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo(forged);
        }
    }

    @Test
    void aUuidThatIsNotInTheFormThisPlatformWritesIsReplaced() throws Exception {
        // UUID.fromString accepts an abbreviated group and zero-pads it, so
        // "1-1-1-1-1" parses. A value this platform would never have generated is
        // replaced anyway, and the round trip is what establishes that — not the
        // fact that it parsed.
        for (String nearMiss : List.of(
                "1-1-1-1-1",
                "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6",
                "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6bZ",
                "3f8b1c2e-9d4a-4f6e-8b7c-1a2d3e4f5a6b-")) {
            MockHttpServletRequest near = new MockHttpServletRequest();
            MockHttpServletResponse nearResponse = new MockHttpServletResponse();
            near.addHeader(CorrelationIdFilter.HEADER_NAME, nearMiss);

            filter.doFilter(near, nearResponse, new MockFilterChain());

            assertItIsAGeneratedUuid(nearResponse.getHeader(CorrelationIdFilter.HEADER_NAME));
            assertThat(nearResponse.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo(nearMiss);
        }
    }

    @Test
    void clearsMdcEvenWhenDownstreamFails() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, A_CALLERS_UUID);
        FilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesDistinctIdsPerRequest() throws Exception {
        filter.doFilter(request, response, new MockFilterChain());
        String first = response.getHeader(CorrelationIdFilter.HEADER_NAME);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), secondResponse, new MockFilterChain());
        String second = secondResponse.getHeader(CorrelationIdFilter.HEADER_NAME);

        assertThat(first).isNotEqualTo(second);
    }
}

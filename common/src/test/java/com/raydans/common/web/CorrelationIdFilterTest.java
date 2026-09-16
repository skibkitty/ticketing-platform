package com.raydans.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import jakarta.servlet.FilterChain;
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

    @Test
    void echoesHeaderWhenPresent() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "incoming-id");

        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("incoming-id");
        assertThat(mdcDuringRequest.get()).isEqualTo("incoming-id");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
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

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER_NAME))).isNotNull();
        assertThat(mdcDuringRequest.get()).isEqualTo(response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void treatsBlankHeaderAsAbsent() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
    }

    @Test
    void clearsMdcEvenWhenDownstreamFails() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "id");
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
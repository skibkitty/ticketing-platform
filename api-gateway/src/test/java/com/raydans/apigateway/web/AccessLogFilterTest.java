package com.raydans.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AccessLogFilter filter = new AccessLogFilter();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void listen() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(AccessLogFilter.class)).addAppender(logged);
    }

    @AfterEach
    void stopListening() {
        ((Logger) LoggerFactory.getLogger(AccessLogFilter.class)).detachAppender(logged);
        logged.stop();
    }

    @Test
    void logsTheStatusTheChainAnsweredWith() throws Exception {
        filter.doFilter(request, response, answeringWith(HttpServletResponse.SC_UNAUTHORIZED));

        assertThat(onlyLine()).contains("GET /api/v1/events -> 401");
    }

    @Test
    void anExceptionThatEscapesIsLoggedAsTheContainersFiveHundredNotTheUntouchedStatus() {
        // Nothing has set a status yet, so the response still says 200. The
        // container turns the exception into a 500 after this filter has
        // returned, and that 500 is the status the caller sees.
        assertThatThrownBy(() -> filter.doFilter(request, response, throwing(new IllegalStateException("boom"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(onlyLine()).contains("GET /api/v1/events -> 500");
    }

    @Test
    void anExceptionAfterTheStatusWasSentIsLoggedWithTheStatusThatWasSent() throws Exception {
        // Once the response is committed the container cannot change the status
        // any more, so the status already sent is the one the caller got.
        MockFilterChain committedThenFailed = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
                ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_OK);
                res.flushBuffer();
                throw new IOException("client went away");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, committedThenFailed))
                .isInstanceOf(IOException.class);

        assertThat(onlyLine()).contains("GET /api/v1/events -> 200");
    }

    private String onlyLine() {
        assertThat(logged.list).hasSize(1);
        return logged.list.get(0).getFormattedMessage();
    }

    private static MockFilterChain answeringWith(int status) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                ((HttpServletResponse) res).setStatus(status);
            }
        };
    }

    private static MockFilterChain throwing(RuntimeException ex) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                throw ex;
            }
        };
    }
}

package com.raydans.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;

/**
 * How the catch-all tells a failed hop from a failure that is not the service's
 * (#13). The payments circuit counts the first and not the second, and a 500 on
 * the wire is the same for both, so the mark on the request is the only place the
 * difference survives.
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler =
            new GatewayExceptionHandler(new ErrorResponseWriter(new ObjectMapper()));

    static Stream<Arguments> failures() {
        return Stream.of(
                // RestClient connecting or waiting for the status.
                Arguments.of(new ResourceAccessException("refused", new ConnectException("Connection refused")), true),
                Arguments.of(new ResourceAccessException("timed out", new SocketTimeoutException("Read timed out")), true),
                // The service's body failing after RestClient returned.
                Arguments.of(new SocketTimeoutException("Read timed out"), true),
                Arguments.of(new IOException("Premature end of Content-Length delimited message body"), true),
                // The caller going away, as Tomcat and the JDK report it.
                Arguments.of(new IOException("Broken pipe"), false),
                Arguments.of(new IOException("Connection reset by peer"), false),
                // The gateway's own defect.
                Arguments.of(new NullPointerException("a gateway bug"), false),
                Arguments.of(new IllegalStateException("a gateway bug"), false));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailedHopIsMarkedAsOneAndAnythingElseIsNot(Exception failure, boolean hopFailed) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments/7");

        assertThat(handler.fallback(failure, request).getStatusCode().value()).isEqualTo(500);

        assertThat(request.getAttribute(GatewayExceptionHandler.HOP_FAILURE_ATTRIBUTE) != null).isEqualTo(hopFailed);
        assertThat(request.getAttribute(GatewayExceptionHandler.GATEWAY_FAULT_ATTRIBUTE) != null).isEqualTo(!hopFailed);
    }
}

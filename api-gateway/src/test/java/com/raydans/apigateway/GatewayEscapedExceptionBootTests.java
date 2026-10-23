package com.raydans.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.apigateway.web.AccessLogFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The access line against what a caller actually receives, when an exception
 * gets past every handler and the container writes the answer itself.
 *
 * <p>A real server rather than MockMvc, because MockMvc rethrows to the test
 * where Tomcat would write a 500, and that 500 is the status in question.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GatewayEscapedExceptionBootTests {

    private static final String EXPLODE = "/api/v1/events/explode";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "a-signing-key-this-test-only-ever-signs-with");
        registry.add("DEMO_CUSTOMER_PASSWORD", () -> "the-customer-password");
        registry.add("DEMO_ORGANIZER_PASSWORD", () -> "the-organizer-password");
        registry.add("DEMO_ADMIN_PASSWORD", () -> "the-admin-password");
    }

    /**
     * A filter failing inside the access log and outside the DispatcherServlet,
     * where no {@code @ExceptionHandler} can reach it: a gateway defect, as the
     * container sees one.
     */
    @TestConfiguration
    static class AFilterThatFails {

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration =
                    new FilterRegistrationBean<>(new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(
                                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                throws java.io.IOException, jakarta.servlet.ServletException {
                            if (EXPLODE.equals(request.getRequestURI())) {
                                throw new IllegalStateException("a gateway defect");
                            }
                            chain.doFilter(request, response);
                        }
                    });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 7);
            return registration;
        }
    }

    @Test
    void theLoggedStatusIsTheOneTheCallerReceived(CapturedOutput output) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EXPLODE)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        // Read as JSON, not as text: the encoder escapes the path's slashes.
        assertThat(accessLines(output))
                .singleElement()
                .satisfies(message -> assertThat(message).startsWith("GET " + EXPLODE + " -> 500 in"));
    }

    private static List<String> accessLines(CapturedOutput output) {
        ObjectMapper json = new ObjectMapper();
        return output.getOut().lines()
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (JsonProcessingException notJson) {
                        return null;
                    }
                })
                .filter(line -> line != null
                        && AccessLogFilter.class.getName().equals(line.path("logger_name").asText()))
                .map(line -> line.path("message").asText())
                .toList();
    }
}

package com.raydans.common.web.cors;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS configuration shared by every service. Bound from {@code app.cors.allowed-origins}.
 *
 * @param allowedOrigins origins permitted by the gateway's CORS policy
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
package com.raydans.common.web.cors;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the shared CORS policy so every service can opt in with
 * {@code app.cors.allowed-origins} and no package scan is required.
 */
@AutoConfiguration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsAutoConfiguration {

    @Bean
    public WebMvcConfigurer corsWebMvcConfigurer(CorsProperties properties) {
        return new CorsWebMvcConfigurer(properties);
    }
}
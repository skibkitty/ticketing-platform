package com.raydans.common.web.cors;

import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the shared {@link CorsProperties} allowed origins to every route.
 */
public class CorsWebMvcConfigurer implements WebMvcConfigurer {

    private final CorsProperties properties;

    public CorsWebMvcConfigurer(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowedOrigins = properties.allowedOrigins().toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
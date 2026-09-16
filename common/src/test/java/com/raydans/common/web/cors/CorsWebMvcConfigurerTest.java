package com.raydans.common.web.cors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = CorsWebMvcConfigurerTest.TestApp.class,
        properties = "app.cors.allowed-origins=https://app.example.com,https://admin.example.com")
@AutoConfigureMockMvc
class CorsWebMvcConfigurerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromAllowedOriginGetsCorsHeaders() throws Exception {
        mvc.perform(options("/api/v1/probe")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
    }

    @Test
    void preflightFromDisallowedOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/probe")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void actualRequestFromAllowedOriginEchoesHeader() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Origin", "https://app.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApp {

        @org.springframework.web.bind.annotation.RestController
        static class ProbeController {
            @org.springframework.web.bind.annotation.GetMapping("/api/v1/probe")
            String probe() {
                return "ok";
            }
        }
    }
}
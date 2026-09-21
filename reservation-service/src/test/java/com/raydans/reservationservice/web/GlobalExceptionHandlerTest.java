package com.raydans.reservationservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.reservationservice.event.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    MockMvc mvc;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @RestController
    static class ProbeController {

        @GetMapping("/probe/seat-unavailable")
        void seatUnavailable() {
            throw new SeatUnavailableException("Seat A-1-12 is already taken");
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Reservation 42 was not found");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("unmodeled");
        }

        @PostMapping("/probe/validate")
        void validate(@RequestBody @jakarta.validation.Valid ProbeCommand command) {}

        record ProbeCommand(@jakarta.validation.constraints.NotNull Long eventId) {}
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void seatUnavailableMapsTo409WithApiErrorResponseShape() throws Exception {
        mvc.perform(get("/probe/seat-unavailable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Seat A-1-12 is already taken"))
                .andExpect(jsonPath("$.path").value("/probe/seat-unavailable"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void notFoundMapsTo404WithApiErrorResponseShape() throws Exception {
        mvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Reservation 42 was not found"));
    }

    @Test
    void validationFailureMapsTo400WithFieldDetails() throws Exception {
        mvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details[0]").value(containsString("eventId")));
    }

    @Test
    void unmodeledErrorMapsTo500ApiErrorResponseNotRaw() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.details").isArray());
    }
}
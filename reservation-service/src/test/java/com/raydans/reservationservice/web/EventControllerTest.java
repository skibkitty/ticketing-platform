package com.raydans.reservationservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.reservationservice.event.DuplicateSeatException;
import com.raydans.reservationservice.event.EventService;
import com.raydans.reservationservice.event.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EventControllerTest {

    EventService events = mock(EventService.class, Mockito.withSettings().stubOnly());

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new EventController(events))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createEventReturnsCreatedEventAndSeatIds() throws Exception {
        when(events.create(any(CreateEventRequest.class)))
                .thenReturn(new CreateEventResponse(7L, List.of(10L, 11L)));

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Night","venue":"Metropolitan Opera",
                                 "eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[
                                   {"section":"Orchestra","row":"A","seatNumber":1},
                                   {"section":"Orchestra","row":"A","seatNumber":2}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(7))
                .andExpect(jsonPath("$.seatIds[0]").value(10))
                .andExpect(jsonPath("$.seatIds[1]").value(11))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", org.hamcrest.Matchers.endsWith("/api/v1/events/7")));
    }

    @Test
    void createEventRejectsUnparsableEventDateAs400() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Night","venue":"Metropolitan Opera",
                                 "eventDate":"not-a-date",
                                 "seats":[{"section":"Orchestra","row":"A","seatNumber":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createEventRejectsUnknownSeatStatusAs400() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Night","venue":"Metropolitan Opera",
                                 "eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[{"section":"Orchestra","row":"A","seatNumber":1,"status":"BANANA"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createEventRejectsMalformedJsonAs400() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Opening Night\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createEventRejectsTooLongNameAs400() throws Exception {
        String longName = "n".repeat(256);
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","venue":"Metropolitan Opera",
                                 "eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[{"section":"Orchestra","row":"A","seatNumber":1}]}"""
                                .formatted(longName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void createEventRejectsTooLongSectionAs400() throws Exception {
        String longSection = "s".repeat(51);
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Night","venue":"Metropolitan Opera",
                                 "eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[{"section":"%s","row":"A","seatNumber":1}]}"""
                                .formatted(longSection)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("section")));
    }

    @Test
    void createEventRejectsMissingNameAs400WithFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venue":"Metropolitan Opera","eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[{"section":"Orchestra","row":"A","seatNumber":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void createEventRejectsDuplicateSeatAs409() throws Exception {
        when(events.create(any(CreateEventRequest.class)))
                .thenThrow(new DuplicateSeatException("Seats must be unique per event: row A seat 1 duplicated"));

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Night","venue":"Metropolitan Opera",
                                 "eventDate":"2026-11-01T19:30:00Z",
                                 "seats":[
                                   {"section":"Orchestra","row":"A","seatNumber":1},
                                   {"section":"Orchestra","row":"A","seatNumber":1}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void listEventsReturnsEventSummaries() throws Exception {
        when(events.list())
                .thenReturn(List.of(new EventSummary(7L, "Opening Night", "Metropolitan Opera",
                        Instant.parse("2026-11-01T19:30:00Z"))));

        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Opening Night"));
    }

    @Test
    void getEventReturnsSummary() throws Exception {
        when(events.get(7L))
                .thenReturn(new EventSummary(7L, "Opening Night", "Metropolitan Opera",
                        Instant.parse("2026-11-01T19:30:00Z")));

        mvc.perform(get("/api/v1/events/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Opening Night"));
    }

    @Test
    void getEventForUnknownIdReturns404() throws Exception {
        when(events.get(99L))
                .thenThrow(new ResourceNotFoundException("Event 99 was not found"));

        mvc.perform(get("/api/v1/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listSeatsFilteredByStatusReturnsSeats() throws Exception {
        when(events.listSeats(7L, SeatStatus.AVAILABLE))
                .thenReturn(List.of(new SeatResponse(10L, "Orchestra", "A", 1, 15000, SeatStatus.AVAILABLE)));

        mvc.perform(get("/api/v1/events/7/seats").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    void listSeatsWithUnknownStatusReturns400() throws Exception {
        mvc.perform(get("/api/v1/events/7/seats").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void listSeatsForUnknownEventReturns404() throws Exception {
        when(events.listSeats(99L, null))
                .thenThrow(new ResourceNotFoundException("Event 99 was not found"));

        mvc.perform(get("/api/v1/events/99/seats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}

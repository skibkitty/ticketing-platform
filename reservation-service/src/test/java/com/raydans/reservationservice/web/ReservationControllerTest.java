package com.raydans.reservationservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.raydans.reservationservice.event.ResourceNotFoundException;
import com.raydans.reservationservice.reservation.ReservationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReservationControllerTest {

    ReservationService reservations = mock(ReservationService.class, Mockito.withSettings().stubOnly());

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ReservationController(reservations))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReservationReturnsCreatedWithLocation() throws Exception {
        when(reservations.create(any(ReservationRequest.class), eq(99L)))
                .thenReturn(reservationResponse(42L));

        mvc.perform(post("/api/v1/reservations")
                        .header(ReservationController.CUSTOMER_HEADER, "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":7,\"seatIds\":[10,11]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/reservations/42")))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.customerId").value(99))
                .andExpect(jsonPath("$.eventId").value(7))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amountCents").value(27000))
                .andExpect(jsonPath("$.seats.length()").value(2));
    }

    @Test
    void createReservationForTakenSeatReturns409() throws Exception {
        when(reservations.create(any(ReservationRequest.class), eq(99L)))
                .thenThrow(new SeatUnavailableException("One or more seats are no longer available"));

        mvc.perform(post("/api/v1/reservations")
                        .header(ReservationController.CUSTOMER_HEADER, "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":7,\"seatIds\":[10]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void createReservationWithoutSeatIdsReturns400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(ReservationController.CUSTOMER_HEADER, "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":7,\"seatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("seatIds")));
    }

    @Test
    void createReservationWithoutEventIdReturns400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .header(ReservationController.CUSTOMER_HEADER, "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[10]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("eventId")));
    }

    @Test
    void getReservationReturnsMappedResponse() throws Exception {
        when(reservations.get(42L)).thenReturn(reservationResponse(42L));

        mvc.perform(get("/api/v1/reservations/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.seats[0].section").value("Orchestra"));
    }

    @Test
    void getUnknownReservationReturns404() throws Exception {
        when(reservations.get(404L))
                .thenThrow(new ResourceNotFoundException("Reservation 404 was not found"));

        mvc.perform(get("/api/v1/reservations/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listReservationsByCustomerIdReturnsList() throws Exception {
        when(reservations.listByCustomerId(99L))
                .thenReturn(List.of(reservationResponse(42L), reservationResponse(43L)));

        mvc.perform(get("/api/v1/reservations").param("customerId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[1].id").value(43));
    }

    @Test
    void listReservationsRequiresCustomerIdParam() throws Exception {
        mvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isBadRequest());
    }

    private ReservationResponse reservationResponse(long id) {
        return new ReservationResponse(
                id,
                99L,
                7L,
                com.raydans.reservationservice.reservation.ReservationStatus.PENDING_PAYMENT,
                Instant.parse("2026-11-01T19:30:00Z"),
                Instant.parse("2026-11-01T19:40:00Z"),
                27000,
                List.of(
                        new SeatResponse(10L, "Orchestra", "A", 1, 15000, SeatStatus.HELD),
                        new SeatResponse(11L, "Orchestra", "B", 2, 12000, SeatStatus.HELD)));
    }
}
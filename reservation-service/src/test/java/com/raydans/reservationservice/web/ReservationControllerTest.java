package com.raydans.reservationservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReservationControllerTest {

    /**
     * A plain mock rather than a {@code stubOnly} one: the ownership assertions
     * need to prove the service was <em>not</em> reached at all when the header
     * is missing or contradicts the caller, and a stub-only mock refuses to be
     * verified. Nothing here relies on lenient stubbing — there is no
     * {@code MockitoExtension} to enforce unused stubs.
     */
    ReservationService reservations = mock(ReservationService.class);

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
    void createReservationWithoutCustomerHeaderReturns400() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":7,\"seatIds\":[10]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
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
        when(reservations.get(42L, 99L)).thenReturn(reservationResponse(42L));

        mvc.perform(get("/api/v1/reservations/42")
                        .header(ReservationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.seats[0].section").value("Orchestra"));
    }

    @Test
    void getReservationAsAnotherCustomerIsNotFound() throws Exception {
        // The bypass this route exists to close. The customerId travels with the
        // lookup, so the service is never asked for 42 on 99's behalf and 99 gets
        // the same answer as for an id that was never issued (ADR 014).
        when(reservations.get(42L, 7L))
                .thenThrow(new ResourceNotFoundException("Reservation 42 was not found"));

        mvc.perform(get("/api/v1/reservations/42")
                        .header(ReservationController.CUSTOMER_HEADER, "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getReservationScopesTheLookupByTheHeaderNotByAnythingInThePath() throws Exception {
        // The id in the path chooses *which* reservation; the header says whose.
        // Both are handed to the service together, so the ownership question is
        // answered downstream of the controller and cannot be left out of it.
        when(reservations.get(42L, 7L)).thenReturn(reservationResponse(42L));

        mvc.perform(get("/api/v1/reservations/42")
                        .header(ReservationController.CUSTOMER_HEADER, "7"))
                .andExpect(status().isOk());

        verify(reservations).get(42L, 7L);
    }

    @Test
    void getUnknownReservationReturns404() throws Exception {
        when(reservations.get(404L, 99L))
                .thenThrow(new ResourceNotFoundException("Reservation 404 was not found"));

        mvc.perform(get("/api/v1/reservations/404")
                        .header(ReservationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getReservationWithoutCustomerHeaderReturns400() throws Exception {
        // Nothing comes through the gateway without this header, so its absence
        // means the request did not come through the gateway at all.
        mvc.perform(get("/api/v1/reservations/42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("X-Customer-Id")));

        verify(reservations, never()).get(anyLong(), anyLong());
    }

    @Test
    void listReservationsReturnsOnlyTheAuthenticatedCustomers() throws Exception {
        when(reservations.listByCustomerId(99L))
                .thenReturn(List.of(reservationResponse(42L), reservationResponse(43L)));

        // No customerId parameter at all: the header is the scope, so listing is
        // a thing you do rather than a Customer you name.
        mvc.perform(get("/api/v1/reservations")
                        .header(ReservationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[1].id").value(43));
    }

    @Test
    void listReservationsIgnoresAQueryParamThatNamesTheCallerThemselves() throws Exception {
        when(reservations.listByCustomerId(99L)).thenReturn(List.of(reservationResponse(42L)));

        // Redundant, not dangerous: a client sending the id it already proved
        // keeps working (ADR 014, on ADR 011's reasoning).
        mvc.perform(get("/api/v1/reservations")
                        .param("customerId", "99")
                        .header(ReservationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(reservations).listByCustomerId(99L);
    }

    @Test
    void listReservationsForAnotherCustomerReturns400RatherThanTheirRows() throws Exception {
        // The list half of the same bypass: naming someone else is refused, and
        // refused rather than quietly ignored, so a client that starts believing
        // the parameter has authority fails its own tests.
        mvc.perform(get("/api/v1/reservations")
                        .param("customerId", "7")
                        .header(ReservationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("does not match the authenticated caller")));

        verify(reservations, never()).listByCustomerId(anyLong());
    }

    @Test
    void listReservationsWithoutCustomerHeaderReturns400() throws Exception {
        mvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("X-Customer-Id")));

        verify(reservations, never()).listByCustomerId(anyLong());
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
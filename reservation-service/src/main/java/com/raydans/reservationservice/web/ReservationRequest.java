package com.raydans.reservationservice.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReservationRequest(
        @NotNull Long eventId,
        @NotNull @NotEmpty List<Long> seatIds) {}
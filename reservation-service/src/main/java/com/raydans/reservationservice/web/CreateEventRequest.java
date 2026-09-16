package com.raydans.reservationservice.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record CreateEventRequest(
        @NotBlank String name,
        @NotBlank String venue,
        @NotNull Instant eventDate,
        @NotEmpty @Valid List<CreateSeatRequest> seats) {}
package com.raydans.reservationservice.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateEventRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String venue,
        @NotNull Instant eventDate,
        @NotEmpty @Valid List<CreateSeatRequest> seats) {}
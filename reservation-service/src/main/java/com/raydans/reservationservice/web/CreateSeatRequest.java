package com.raydans.reservationservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateSeatRequest(
        @NotBlank @Size(max = 50) String section,
        @NotBlank @Size(max = 50) String row,
        @Positive int seatNumber,
        @PositiveOrZero Integer priceCents,
        SeatStatus status) {}
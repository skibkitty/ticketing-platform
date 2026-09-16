package com.raydans.reservationservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateSeatRequest(
        @NotBlank String section,
        @NotBlank String row,
        @Positive int seatNumber,
        SeatStatus status) {}
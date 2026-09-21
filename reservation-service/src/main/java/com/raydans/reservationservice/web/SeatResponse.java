package com.raydans.reservationservice.web;

public record SeatResponse(long id, String section, String row, int seatNumber, int priceCents, SeatStatus status) {}
package com.raydans.reservationservice.web;

import com.raydans.reservationservice.event.SeatEntity;

public record SeatResponse(long id, String section, String row, int seatNumber, int priceCents, SeatStatus status) {

    public static SeatResponse from(SeatEntity seat) {
        return new SeatResponse(
                seat.getId(), seat.getSection(), seat.getRow(), seat.getSeatNumber(),
                seat.getPriceCents(), seat.getStatus());
    }
}
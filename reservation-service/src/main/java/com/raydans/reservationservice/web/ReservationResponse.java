package com.raydans.reservationservice.web;

import com.raydans.reservationservice.reservation.ReservationStatus;
import java.time.Instant;
import java.util.List;

public record ReservationResponse(
        long id,
        long customerId,
        long eventId,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt,
        int amountCents,
        List<SeatResponse> seats) {}
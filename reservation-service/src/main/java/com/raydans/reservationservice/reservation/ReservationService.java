package com.raydans.reservationservice.reservation;

import com.raydans.reservationservice.web.ReservationRequest;
import com.raydans.reservationservice.web.ReservationResponse;
import java.util.List;

public interface ReservationService {

    ReservationResponse create(ReservationRequest request, long customerId);

    /**
     * The Reservation identified by {@code reservationId}, but only if
     * {@code customerId} owns it.
     *
     * <p>The Customer is a required argument rather than something the caller
     * checks afterwards, so the ownership rule cannot be left out by a second
     * controller or another call path reaching this service directly (ADR 014).
     * A Reservation that does not exist and one belonging to another Customer are
     * both a 404, so this answer does not disclose which ids are taken.
     */
    ReservationResponse get(long reservationId, long customerId);

    /** The reservations belonging to {@code customerId}, and no others. */
    List<ReservationResponse> listByCustomerId(long customerId);
}
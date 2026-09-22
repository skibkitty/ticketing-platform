package com.raydans.reservationservice.reservation;

import com.raydans.reservationservice.web.ReservationRequest;
import com.raydans.reservationservice.web.ReservationResponse;
import java.util.List;

public interface ReservationService {

    ReservationResponse create(ReservationRequest request, long customerId);

    ReservationResponse get(long reservationId);

    List<ReservationResponse> listByCustomerId(long customerId);
}
package com.raydans.reservationservice.event;

import com.raydans.reservationservice.web.CreateEventRequest;
import com.raydans.reservationservice.web.CreateEventResponse;
import com.raydans.reservationservice.web.EventSummary;
import com.raydans.reservationservice.web.SeatResponse;
import com.raydans.reservationservice.web.SeatStatus;
import java.util.List;

/** Read/write surface over the Event–Seat model. */
public interface EventService {

    CreateEventResponse create(CreateEventRequest request);

    EventSummary get(Long eventId);

    List<EventSummary> list();

    List<SeatResponse> listSeats(Long eventId, SeatStatus status);
}
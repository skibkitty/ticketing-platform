package com.raydans.reservationservice.web;

import java.util.List;

/** Read/write surface over the Event–Seat model. */
public interface EventService {

    CreateEventResponse create(CreateEventRequest request);

    List<EventSummary> list();

    List<SeatResponse> listSeats(Long eventId, String status);
}

package com.raydans.reservationservice.web;

import java.util.List;

public record CreateEventResponse(long eventId, List<Long> seatIds) {}
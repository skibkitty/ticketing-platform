package com.raydans.reservationservice.web;

import java.time.Instant;

public record EventSummary(long id, String name, String venue, Instant eventDate) {}
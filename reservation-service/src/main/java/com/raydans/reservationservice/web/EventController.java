package com.raydans.reservationservice.web;

import com.raydans.reservationservice.event.EventService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @PostMapping
    ResponseEntity<CreateEventResponse> create(
            @Valid @RequestBody CreateEventRequest request, UriComponentsBuilder builder) {
        CreateEventResponse created = events.create(request);
        URI location = builder.path("/api/v1/events/{id}/seats").buildAndExpand(created.eventId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    List<EventSummary> listEvents() {
        return events.list();
    }

    @GetMapping("/{eventId}/seats")
    List<SeatResponse> listSeats(
            @PathVariable("eventId") Long eventId,
            @RequestParam(value = "status", required = false) SeatStatus status) {
        return events.listSeats(eventId, status);
    }
}

package com.raydans.reservationservice.event;

import com.raydans.reservationservice.web.CreateEventRequest;
import com.raydans.reservationservice.web.CreateEventResponse;
import com.raydans.reservationservice.web.CreateSeatRequest;
import com.raydans.reservationservice.web.DuplicateSeatException;
import com.raydans.reservationservice.web.EventService;
import com.raydans.reservationservice.web.EventSummary;
import com.raydans.reservationservice.web.ResourceNotFoundException;
import com.raydans.reservationservice.web.SeatResponse;
import com.raydans.reservationservice.web.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaEventService implements EventService {

    private final EventRepository events;
    private final SeatRepository seats;

    public JpaEventService(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @Override
    @Transactional
    public CreateEventResponse create(CreateEventRequest request) {
        requireUniqueSeats(request);
        EventEntity event = events.save(new EventEntity(request.name(), request.venue(), request.eventDate()));
        List<Long> seatIds;
        try {
            seatIds = request.seats().stream()
                    .map(s -> seats.save(new SeatEntity(event, s.section(), s.row(), s.seatNumber(), statusOf(s))))
                    .map(SeatEntity::getId)
                    .toList();
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateSeatException(
                    "Seats must be unique per event: duplicate section/row/seatNumber detected");
        }
        return new CreateEventResponse(event.getId(), seatIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSummary> list() {
        return events.findAll().stream()
                .map(e -> new EventSummary(e.getId(), e.getName(), e.getVenue(), e.getEventDate().toString()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatResponse> listSeats(Long eventId, String status) {
        if (!events.existsById(eventId)) {
            throw new ResourceNotFoundException("Event " + eventId + " was not found");
        }
        List<SeatEntity> seats = status == null
                ? this.seats.findByEvent_IdOrderById(eventId)
                : this.seats.findByEvent_IdAndStatusOrderById(eventId, SeatStatus.valueOf(status));
        return seats.stream()
                .map(s -> new SeatResponse(s.getId(), s.getSection(), s.getRow(), s.getSeatNumber(), s.getStatus()))
                .toList();
    }

    private void requireUniqueSeats(CreateEventRequest request) {
        List<String> keys = request.seats().stream()
                .map(s -> s.section() + "|" + s.row() + "|" + s.seatNumber())
                .toList();
        boolean duplicate = keys.stream().distinct().count() != keys.size();
        if (duplicate) {
            throw new DuplicateSeatException("Seats must be unique per event: duplicate section/row/seatNumber detected");
        }
    }

    private SeatStatus statusOf(CreateSeatRequest seat) {
        return seat.status() == null ? SeatStatus.AVAILABLE : seat.status();
    }
}
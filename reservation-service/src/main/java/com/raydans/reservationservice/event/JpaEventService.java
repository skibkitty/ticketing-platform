package com.raydans.reservationservice.event;

import com.raydans.reservationservice.web.CreateEventRequest;
import com.raydans.reservationservice.web.CreateEventResponse;
import com.raydans.reservationservice.web.CreateSeatRequest;
import com.raydans.reservationservice.web.DuplicateSeatException;
import com.raydans.reservationservice.web.EventSummary;
import com.raydans.reservationservice.web.ResourceNotFoundException;
import com.raydans.reservationservice.web.SeatResponse;
import com.raydans.reservationservice.web.SeatStatus;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaEventService implements EventService {

    private static final String SEAT_UNIQUENESS_CONSTRAINT = "uq_seats_event_section_row_number";
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String DUPLICATE_MESSAGE =
            "Seats must be unique per event: duplicate section/row/seatNumber detected";

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
            throw translateIntegrityViolation(ex);
        }
        return new CreateEventResponse(event.getId(), seatIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSummary> list() {
        return events.findAll().stream()
                .map(e -> new EventSummary(e.getId(), e.getName(), e.getVenue(), e.getEventDate()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatResponse> listSeats(Long eventId, SeatStatus status) {
        if (!events.existsById(eventId)) {
            throw new ResourceNotFoundException("Event " + eventId + " was not found");
        }
        List<SeatEntity> seats = status == null
                ? this.seats.findByEvent_IdOrderById(eventId)
                : this.seats.findByEvent_IdAndStatusOrderById(eventId, status);
        return seats.stream()
                .map(s -> new SeatResponse(s.getId(), s.getSection(), s.getRow(), s.getSeatNumber(), s.getStatus()))
                .toList();
    }

    private void requireUniqueSeats(CreateEventRequest request) {
        long distinctKeys = request.seats().stream()
                .map(s -> new SeatKey(s.section(), s.row(), s.seatNumber()))
                .distinct()
                .count();
        if (distinctKeys != request.seats().size()) {
            throw new DuplicateSeatException(DUPLICATE_MESSAGE);
        }
    }

    private DataIntegrityViolationException translateIntegrityViolation(DataIntegrityViolationException ex) {
        if (isSeatUniquenessViolation(ex)) {
            throw new DuplicateSeatException(DUPLICATE_MESSAGE);
        }
        throw ex;
    }

    private boolean isSeatUniquenessViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && SEAT_UNIQUENESS_CONSTRAINT.equals(cve.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION_SQLSTATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private SeatStatus statusOf(CreateSeatRequest seat) {
        return seat.status() == null ? SeatStatus.AVAILABLE : seat.status();
    }

    private record SeatKey(String section, String row, int seatNumber) {}
}
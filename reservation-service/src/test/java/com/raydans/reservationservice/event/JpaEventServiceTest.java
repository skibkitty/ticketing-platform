package com.raydans.reservationservice.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.raydans.reservationservice.web.CreateEventRequest;
import com.raydans.reservationservice.web.CreateSeatRequest;
import com.raydans.reservationservice.web.DuplicateSeatException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class JpaEventServiceTest {

    static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    static final String FOREIGN_KEY_SQLSTATE = "23503";

    @Mock
    EventRepository events;

    @Mock
    SeatRepository seats;

    JpaEventService service;

    CreateEventRequest validRequest =
            new CreateEventRequest(
                    "Opening Night",
                    "Metropolitan Opera",
                    Instant.parse("2026-11-01T19:30:00Z"),
                    List.of(new CreateSeatRequest("Orchestra", "A", 1, null)));

    @BeforeEach
    void setUp() {
        service = new JpaEventService(events, seats);
    }

    @Test
    void duplicateSeatsInOneRequestAreRejectedBeforePersisting() {
        CreateEventRequest request =
                new CreateEventRequest(
                        "Opening Night",
                        "Metropolitan Opera",
                        Instant.parse("2026-11-01T19:30:00Z"),
                        List.of(
                                new CreateSeatRequest("Orchestra", "A", 1, null),
                                new CreateSeatRequest("Orchestra", "A", 1, null)));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(DuplicateSeatException.class);
    }

    @Test
    void uniqueSeatConstraintViolationIsReportedAsDuplicateSeat() {
        when(events.save(any())).thenReturn(new EventEntity("Opening Night", "Metropolitan Opera", Instant.now()));
        when(seats.save(any())).thenThrow(uniqueViolation("uq_seats_event_section_row_number"));

        assertThatThrownBy(() -> service.create(validRequest)).isInstanceOf(DuplicateSeatException.class);
    }

    @Test
    void uniqueViolationBySqlStateIsReportedAsDuplicateSeatEvenWithoutConstraintName() {
        when(events.save(any())).thenReturn(new EventEntity("Opening Night", "Metropolitan Opera", Instant.now()));
        when(seats.save(any())).thenThrow(uniqueViolationBySqlState(UNIQUE_VIOLATION_SQLSTATE));

        assertThatThrownBy(() -> service.create(validRequest)).isInstanceOf(DuplicateSeatException.class);
    }

    @Test
    void unrelatedIntegrityViolationIsNotReportedAsDuplicateSeat() {
        when(events.save(any())).thenReturn(new EventEntity("Opening Night", "Metropolitan Opera", Instant.now()));
        when(seats.save(any())).thenThrow(uniqueViolation("uq_other_things_constraint"));

        assertThatThrownBy(() -> service.create(validRequest)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonUniqueIntegrityViolationIsNotReportedAsDuplicateSeat() {
        when(events.save(any())).thenReturn(new EventEntity("Opening Night", "Metropolitan Opera", Instant.now()));
        when(seats.save(any())).thenThrow(uniqueViolationBySqlState(FOREIGN_KEY_SQLSTATE));

        assertThatThrownBy(() -> service.create(validRequest)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private DataIntegrityViolationException uniqueViolation(String constraintName) {
        org.hibernate.exception.ConstraintViolationException cause =
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint \"" + constraintName + "\"",
                        new SQLException("duplicate key"),
                        constraintName);
        return new DataIntegrityViolationException("StatementCallback; SQL", cause);
    }

    private DataIntegrityViolationException uniqueViolationBySqlState(String sqlState) {
        return new DataIntegrityViolationException(
                "StatementCallback; SQL", new SQLException("violates constraint", sqlState));
    }
}
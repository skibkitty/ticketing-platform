package com.raydans.reservationservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import com.raydans.reservationservice.web.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryServiceTest {

    static final String CORRELATION_ID = "sweep-correlation-id";

    @Mock
    OutboxEventRepository outbox;

    final ObjectMapper objectMapper = new ObjectMapper();

    ReservationExpiryService expiry;

    @BeforeEach
    void setUp() {
        expiry = new ReservationExpiryService(outbox, objectMapper);
        MDC.put("correlationId", CORRELATION_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove("correlationId");
    }

    @Test
    void expiresOverdueReservationReleasesLapsedSeatsAndStagesExactlyOneEvent() {
        SeatEntity seat = heldSeat(10L, Instant.now().minusSeconds(1));
        ReservationEntity overdue = reservation(42L, List.of(seat), Instant.now().minusSeconds(1));

        List<SeatEntity> released = expiry.expireIfOverdue(overdue, Instant.now());

        assertThat(released).containsExactly(seat);
        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldExpiresAt()).isNull();

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(captor.capture());
        OutboxEventEntity event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("reservation.ReservationExpired");
        assertThat(event.getAggregateType()).isEqualTo("Reservation");
        assertThat(event.getAggregateId()).isEqualTo(42L);
        assertThat(event.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.getPayload())
                .contains("\"reservationId\":42")
                .contains("\"customerId\":99")
                .contains("\"eventId\":7")
                .contains("\"seatIds\":[10]")
                .contains("\"amountCents\":15000");
    }

    @Test
    void expiresOverdueReservationWhoseSeatWasReHeldStagesEmptyEvent() {
        SeatEntity seat = heldSeat(10L, Instant.now().plusSeconds(600));
        ReservationEntity overdue = reservation(42L, List.of(seat), Instant.now().minusSeconds(1));

        List<SeatEntity> released = expiry.expireIfOverdue(overdue, Instant.now());

        assertThat(released).isEmpty();
        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"seatIds\":[]").contains("\"amountCents\":0");
    }

    @Test
    void freshReservationIsLeftUntouchedAndNothingIsStaged() {
        SeatEntity seat = heldSeat(10L, Instant.now().plusSeconds(600));
        ReservationEntity fresh = reservation(42L, List.of(seat), Instant.now().plusSeconds(600));

        List<SeatEntity> released = expiry.expireIfOverdue(fresh, Instant.now());

        assertThat(released).isEmpty();
        assertThat(fresh.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void alreadyExpiredReservationIsLeftUntouchedAndNothingIsStaged() {
        SeatEntity seat = heldSeat(10L, Instant.now().plusSeconds(600));
        ReservationEntity expired = reservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        expired.markExpired();

        List<SeatEntity> released = expiry.expireIfOverdue(expired, Instant.now());

        assertThat(released).isEmpty();
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    private static SeatEntity heldSeat(long id, Instant holdExpiresAt) {
        SeatEntity entity = new SeatEntity(event(7L), "Orchestra", "A", 1, 15000, SeatStatus.HELD);
        ReflectionTestUtils.setField(entity, "id", id);
        entity.flipToHeld(holdExpiresAt);
        return entity;
    }

    private static ReservationEntity reservation(long id, List<SeatEntity> heldSeats, Instant expiresAt) {
        ReservationEntity entity = new ReservationEntity(
                99L, event(7L), ReservationStatus.PENDING_PAYMENT, expiresAt);
        ReflectionTestUtils.setField(entity, "id", id);
        entity.getSeats().addAll(heldSeats);
        return entity;
    }

    private static EventEntity event(long id) {
        EventEntity e = new EventEntity(
                "Opening Night", "Metropolitan Opera", Instant.parse("2026-11-01T19:30:00Z"));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
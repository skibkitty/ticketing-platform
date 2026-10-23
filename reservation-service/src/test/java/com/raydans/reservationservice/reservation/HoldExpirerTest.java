package com.raydans.reservationservice.reservation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.raydans.common.web.CorrelationIdFilter;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.reservationservice.event.EventEntity;
import com.raydans.reservationservice.event.SeatEntity;
import com.raydans.reservationservice.event.SeatRepository;
import com.raydans.reservationservice.outbox.OutboxEventEntity;
import com.raydans.reservationservice.outbox.OutboxEventRepository;
import com.raydans.reservationservice.web.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldExpirerTest {

    @Mock
    SeatRepository seats;

    @Mock
    ReservationRepository reservations;

    @Mock
    OutboxEventRepository outbox;

    final ObjectMapper objectMapper = new ObjectMapper();

    ReservationExpiryService expiry;

    HoldExpirer expirer;

    @BeforeEach
    void setUp() {
        expiry = new ReservationExpiryService(outbox, objectMapper);
        expirer = new HoldExpirer(seats, reservations, expiry);
    }

    @Test
    void expiresOverdueReservationAndReleasesExactlyItsLapsedSeats() {
        SeatEntity seat = heldSeat(10L, Instant.now().minusSeconds(1));
        ReservationEntity overdue =
                pendingReservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldExpiresAt()).isNull();
        verify(reservations).saveAll(List.of(overdue));
        verify(seats).saveAll(List.of(seat));

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEventEntity expired = outboxCaptor.getValue();
        assertThat(expired.getEventType()).isEqualTo("reservation.ReservationExpired");
        assertThat(expired.getAggregateType()).isEqualTo("Reservation");
        assertThat(expired.getAggregateId()).isEqualTo(42L);
        assertThat(expired.getCorrelationId()).isNotNull();
        assertThat(expired.getPayload())
                .contains("\"reservationId\":42")
                .contains("\"customerId\":99")
                .contains("\"eventId\":7")
                .contains("\"seatIds\":[10]")
                .contains("\"amountCents\":15000");
    }

    @Test
    void doesNotReleaseSeatWhoseHoldIsStillLive() {
        SeatEntity seat = heldSeat(10L, Instant.now().plusSeconds(600));
        ReservationEntity overdue =
                pendingReservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));

        expirer.expire();

        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(reservations).saveAll(List.of(overdue));
        verify(seats, never()).saveAll(any());

        // The reservation is still past its expiresAt, so it expires and publishes even though
        // the sweep released nothing — a concurrently re-held seat must never be listed (it is
        // another holder's now). This mirrors the ReservationCancelled convention: the payload
        // lists exactly the seats the sweep actually released, here none. Empty releasedSeats =>
        // seatIds "[]" and amountCents 0, matching the releaseHold() contract in ADR 007.
        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEventEntity expired = outboxCaptor.getValue();
        assertThat(expired.getEventType()).isEqualTo("reservation.ReservationExpired");
        assertThat(expired.getAggregateId()).isEqualTo(42L);
        assertThat(expired.getPayload())
                .contains("\"seatIds\":[]")
                .contains("\"amountCents\":0");
    }

    @Test
    void doesNothingWhenNoReservationIsOverdue() {
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of());

        expirer.expire();

        verify(reservations, never()).saveAll(any());
        verify(seats, never()).saveAll(any());
        verify(outbox, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    void staleSeatSaveConflictIsPropagatedSoTheExpiryTransactionRollsBack() {
        SeatEntity seat = heldSeat(10L, Instant.now().minusSeconds(1));
        ReservationEntity overdue =
                pendingReservation(42L, List.of(seat), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));
        when(seats.saveAll(List.of(seat)))
                .thenThrow(new ObjectOptimisticLockingFailureException(SeatEntity.class, 10L));

        // The @Version guard on a concurrently re-held seat surfaces as a hard failure that
        // propagates out of expire() — never swallowed — so the surrounding @Transactional rolls
        // the whole sweep back (the EXPIRED transition, the seat releases, and the
        // ReservationExpired outbox row staged in the same unit of work). DB-atomicity on the
        // rollback is proven end-to-end by the boot tests; here we pin that the sweep does not
        // mask the conflict.
        assertThatThrownBy(() -> expirer.expire())
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(outbox).save(any(OutboxEventEntity.class));
    }

    private static SeatEntity heldSeat(long id, Instant holdExpiresAt) {
        SeatEntity entity = new SeatEntity(event(7L), "Orchestra", "A", 1, 15000, SeatStatus.HELD);
        ReflectionTestUtils.setField(entity, "id", id);
        entity.flipToHeld(holdExpiresAt);
        return entity;
    }

    private static ReservationEntity pendingReservation(long id, List<SeatEntity> heldSeats, Instant expiresAt) {
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

    @Test
    void eachSweptExpiryIsLoggedUnderTheIdItsEventCarries() {
        // The sweep acts for no request, so it mints the id. What matters is that
        // it is one id per expiry and the same one in the log and on the event,
        // or the Notification at the far end could not be traced back to here.
        ReservationEntity first = pendingReservation(
                42L, List.of(heldSeat(10L, Instant.now().minusSeconds(1))), Instant.now().minusSeconds(1));
        ReservationEntity second = pendingReservation(
                43L, List.of(heldSeat(11L, Instant.now().minusSeconds(1))), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(first, second));
        ListAppender<ILoggingEvent> logged = listen(ReservationExpiryService.class);
        try {
            expirer.expire();
        } finally {
            stopListening(ReservationExpiryService.class, logged);
        }

        ArgumentCaptor<OutboxEventEntity> staged = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox, org.mockito.Mockito.times(2)).save(staged.capture());
        List<String> stagedIds = staged.getAllValues().stream().map(OutboxEventEntity::getCorrelationId).toList();
        List<String> loggedIds = logged.list.stream()
                .map(event -> event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY))
                .toList();

        assertThat(loggedIds).containsExactlyElementsOf(stagedIds).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void aSweepGivesBackTheCorrelationIdItFoundRatherThanClearingIt() {
        ReservationEntity overdue = pendingReservation(
                42L, List.of(heldSeat(10L, Instant.now().minusSeconds(1))), Instant.now().minusSeconds(1));
        when(reservations.findByStatusAndExpiresAtBefore(
                        eq(ReservationStatus.PENDING_PAYMENT), any(Instant.class)))
                .thenReturn(List.of(overdue));
        MDC.put(CorrelationIdFilter.MDC_KEY, "the-callers-id");
        try {
            expirer.expire();

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("the-callers-id");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private static ListAppender<ILoggingEvent> listen(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void stopListening(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }
}

package com.raydans.notificationservice.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JpaNotificationServiceTest {

    static final String CONFIRMED = "reservation.ReservationConfirmed";
    static final String CANCELLED = "reservation.ReservationCancelled";
    static final String EXPIRED = "reservation.ReservationExpired";

    @Mock
    ProcessedEventRepository processedEvents;

    @Mock
    NotificationRepository notifications;

    final ObjectMapper objectMapper = new ObjectMapper();

    static final int MAX_PAGE_SIZE = 200;

    JpaNotificationService service;

    /**
     * The simulated send is this service's user-visible effect, and with the repositories
     * mocked it is the only outcome left to observe — so the dedupe tests assert the number
     * of sends, not merely that the claim was attempted.
     */
    ListAppender<ILoggingEvent> sends = new ListAppender<>();

    @BeforeEach
    void setUp() {
        service = new JpaNotificationService(processedEvents, notifications, objectMapper, MAX_PAGE_SIZE);
        sends.start();
        serviceLogger().addAppender(sends);
    }

    @AfterEach
    void tearDown() {
        serviceLogger().detachAppender(sends);
        sends.stop();
    }

    private static Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(JpaNotificationService.class);
    }

    private List<String> simulatedSends() {
        return sends.list.stream()
                .filter(event -> event.getFormattedMessage().contains("Simulated send"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void confirmedEventRecordsOneNotificationForTheCustomerWhoReserved() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(101L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, CONFIRMED, 101L, 99L, List.of(10L, 11L), 45000));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .tryClaim(eq(99L), eq(101L), eq("RESERVATION_CONFIRMED"), payload.capture());
        assertThat(payload.getValue()).contains("\"message\"");
        assertThat(payload.getValue()).contains("Your reservation 101 is confirmed.");
        assertThat(payload.getValue()).contains("Seats 10, 11 are yours.");
        assertThat(simulatedSends())
                .as("one terminal event is exactly one simulated send")
                .hasSize(1);
    }

    @Test
    void cancelledEventIsRecordedUnderItsOwnTypeAndWording() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(102L), eq("RESERVATION_CANCELLED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, CANCELLED, 102L, 99L, List.of(10L), 45000));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .tryClaim(eq(99L), eq(102L), eq("RESERVATION_CANCELLED"), payload.capture());
        assertThat(payload.getValue()).contains("was cancelled because the payment was declined");
        assertThat(payload.getValue()).contains("Seat 10 has been released.");
    }

    @Test
    void expiredEventIsRecordedUnderItsOwnTypeAndWording() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(103L), eq("RESERVATION_EXPIRED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, EXPIRED, 103L, 99L, List.of(10L), 45000));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).tryClaim(eq(99L), eq(103L), eq("RESERVATION_EXPIRED"), payload.capture());
        assertThat(payload.getValue()).contains("expired before payment completed");
    }

    @Test
    void expiryThatReleasedNoSeatsStillNotifies() {
        // The upstream contract allows this: seatIds/amountCents describe the seats a
        // transition actually released, which is none when a hold was re-extended
        // before the sweep saw it. Rejecting it would poison a legitimate event.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(104L), eq("RESERVATION_EXPIRED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, EXPIRED, 104L, 99L, List.of(), 0));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).tryClaim(eq(99L), eq(104L), eq("RESERVATION_EXPIRED"), payload.capture());
        assertThat(payload.getValue()).contains("Your reservation 104 expired before payment completed.");
        assertThat(payload.getValue()).doesNotContain("Seat");
    }

    @Test
    void duplicateDeliveryOfTheSameEventIdRecordsNoSecondNotification() {
        UUID eventId = UUID.randomUUID();
        // The eventId claim loses: this delivery has been handled already.
        when(processedEvents.tryClaim(eventId)).thenReturn(0);

        service.record(envelope(eventId, CONFIRMED, 105L, 99L, List.of(10L), 45000));

        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
        assertThat(simulatedSends())
                .as("a redelivered event must not tell the customer a second time")
                .isEmpty();
    }

    @Test
    void aDifferentEventIdForAnAlreadyNotifiedTransitionRecordsNoSecondNotification() {
        UUID firstEventId = UUID.randomUUID();
        UUID repeatEventId = UUID.randomUUID();

        when(processedEvents.tryClaim(firstEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(106L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);
        service.record(envelope(firstEventId, CONFIRMED, 106L, 99L, List.of(10L), 45000));

        // A different eventId for a transition the customer was already told about:
        // claimed as processed, but no second notification and nothing thrown.
        when(processedEvents.tryClaim(repeatEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(106L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(0);
        service.record(envelope(repeatEventId, CONFIRMED, 106L, 99L, List.of(10L), 45000));

        verify(processedEvents).tryClaim(firstEventId);
        verify(processedEvents).tryClaim(repeatEventId);
        verify(notifications, times(2)).tryClaim(eq(99L), eq(106L), eq("RESERVATION_CONFIRMED"), anyString());
        assertThat(simulatedSends())
                .as("the customer is told once, not once per event id")
                .hasSize(1);
    }

    @Test
    void theSameReservationReachedThroughAnotherTerminalTypeIsStillItsOwnNotification() {
        // A Reservation owes one Notification per terminal state, so a distinct type is
        // never suppressed by the (reservation, type) claim.
        UUID confirmedEventId = UUID.randomUUID();
        when(processedEvents.tryClaim(confirmedEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(107L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);
        service.record(envelope(confirmedEventId, CONFIRMED, 107L, 99L, List.of(10L), 45000));

        UUID expiredEventId = UUID.randomUUID();
        when(processedEvents.tryClaim(expiredEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(107L), eq("RESERVATION_EXPIRED"), anyString()))
                .thenReturn(1);
        service.record(envelope(expiredEventId, EXPIRED, 107L, 99L, List.of(10L), 45000));

        verify(notifications).tryClaim(eq(99L), eq(107L), eq("RESERVATION_CONFIRMED"), anyString());
        verify(notifications).tryClaim(eq(99L), eq(107L), eq("RESERVATION_EXPIRED"), anyString());
    }

    @Test
    void failedNotificationWorkIsRetriedFromTheTopOnTheNextDelivery() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(108L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenThrow(new IllegalStateException("notification exploded"));

        assertThatThrownBy(() -> service.record(envelope(eventId, CONFIRMED, 108L, 99L, List.of(10L), 45000)))
                .isInstanceOf(IllegalStateException.class);

        // The failure reaches the caller, so the container retries — and the next
        // delivery reaches both claims again, because nothing marked the event done.
        when(notifications.tryClaim(eq(99L), eq(108L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, CONFIRMED, 108L, 99L, List.of(10L), 45000));

        verify(processedEvents, times(2)).tryClaim(eventId);
        verify(notifications, times(2)).tryClaim(eq(99L), eq(108L), eq("RESERVATION_CONFIRMED"), anyString());
        assertThat(simulatedSends())
                .as("the retry is the send that counts, and there is exactly one")
                .hasSize(1);
        // What this cannot show: that the failed claim actually rolled back. There is no
        // transaction manager over mocked repositories here, so the rollback itself is
        // proven against the real database by the boot test's
        // processed_events row count staying at zero for a poisoned event.
    }

    @Test
    void reservationCreatedIsIgnoredWithoutClaimingAnything() {
        // Another service's event on the shared topic, not a malformed record.
        service.record(envelope(UUID.randomUUID(), "reservation.ReservationCreated", 109L, 99L, List.of(10L), 45000));

        verify(processedEvents, never()).tryClaim(any());
        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void anUnrecognisedEventTypeIsIgnoredWithoutClaimingAnything() {
        service.record(envelope(UUID.randomUUID(), "reservation.ReservationReinstated", 110L, 99L, List.of(10L), 45000));

        verify(processedEvents, never()).tryClaim(any());
        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void blankEventTypeIsRejectedAsMalformed() {
        assertThatThrownBy(() -> service.record(new EventEnvelope<>(
                        UUID.randomUUID(), "  ", Instant.now(), "cid", new UUID(0L, 111L), payload(111L, 99L, List.of(10L), 45000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        verify(processedEvents, never()).tryClaim(any());
        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void nullEventIdIsRejectedAsMalformed() {
        assertThatThrownBy(() -> service.record(new EventEnvelope<>(
                        null, CONFIRMED, Instant.now(), "cid", new UUID(0L, 112L), payload(112L, 99L, List.of(10L), 45000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void nullPayloadIsRejectedAsMalformed() {
        assertThatThrownBy(() -> service.record(
                        new EventEnvelope<>(UUID.randomUUID(), CONFIRMED, Instant.now(), "cid", new UUID(0L, 113L), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void missingReservationIdIsRejected() {
        assertThatThrownBy(() -> service.record(envelope(
                        UUID.randomUUID(), CONFIRMED, 114L, 99L, List.of(10L), 45000, "reservationId")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void zeroReservationIdIsRejected() {
        assertThatThrownBy(() -> service.record(envelope(UUID.randomUUID(), CONFIRMED, 0L, 99L, List.of(10L), 45000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationId");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void missingCustomerIdIsRejectedBecauseTheRecipientIsTheWholePoint() {
        assertThatThrownBy(() -> service.record(envelope(
                        UUID.randomUUID(), CONFIRMED, 115L, 99L, List.of(10L), 45000, "customerId")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");

        verify(processedEvents, never()).tryClaim(any());
        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void nonPositiveCustomerIdIsRejected() {
        assertThatThrownBy(() -> service.record(envelope(UUID.randomUUID(), CONFIRMED, 116L, 0L, List.of(10L), 45000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");

        verify(notifications, never()).tryClaim(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void aMissingPayloadEventIdStillNotifiesTheCustomer() {
        // The payload's eventId is the Event the Reservation is for. Nothing in the
        // wording mentions it, and the only id this service trusts for idempotency is
        // the envelope's — already required to be set — so a payload without it is not a
        // reason to deny the Customer their notification.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.tryClaim(eventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(117L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);

        service.record(envelope(eventId, CONFIRMED, 117L, 99L, List.of(10L), 45000, "eventId"));

        assertThat(simulatedSends()).hasSize(1);
    }

    @Test
    void missingSeatIdsAreRejected() {
        assertThatThrownBy(() -> service.record(envelope(
                        UUID.randomUUID(), CONFIRMED, 118L, 99L, List.of(10L), 45000, "seatIds")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seatIds");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void seatIdsContainingNonPositiveIdsAreRejected() {
        assertThatThrownBy(() -> service.record(envelope(UUID.randomUUID(), CONFIRMED, 119L, 99L, List.of(10L, 0L), 45000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seatIds");

        verify(processedEvents, never()).tryClaim(any());
    }

    @Test
    void anAmountTheNotificationNeverMentionsCannotQuarantineIt() {
        // amountCents rides along on the upstream contract, but no wording here quotes
        // it. A producer bug in a field this message does not use is not a reason to
        // take away the one message that tells the Customer what happened — neither a
        // nonsensical amount nor a missing one.
        UUID negativeAmountEventId = UUID.randomUUID();
        when(processedEvents.tryClaim(negativeAmountEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(120L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);
        service.record(envelope(negativeAmountEventId, CONFIRMED, 120L, 99L, List.of(10L), -100));

        UUID missingAmountEventId = UUID.randomUUID();
        when(processedEvents.tryClaim(missingAmountEventId)).thenReturn(1);
        when(notifications.tryClaim(eq(99L), eq(121L), eq("RESERVATION_CONFIRMED"), anyString()))
                .thenReturn(1);
        service.record(envelope(missingAmountEventId, CONFIRMED, 121L, 99L, List.of(10L), 45000, "amountCents"));

        assertThat(simulatedSends()).hasSize(2);
    }

    @Test
    void listForCustomerReturnsTheirNotificationsWithTheStoredMessage() {
        NotificationEntity newest = row(2L, 99L, 202L, NotificationType.RESERVATION_EXPIRED,
                "{\"message\":\"Your reservation 202 expired before payment completed.\"}");
        NotificationEntity older = row(1L, 99L, 201L, NotificationType.RESERVATION_CONFIRMED,
                "{\"message\":\"Your reservation 201 is confirmed. Seats 10 are yours.\"}");
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of(newest, older));

        List<NotificationResponse> listed = service.listForCustomer(99L, 50, null);

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).id()).isEqualTo(2L);
        assertThat(listed.get(0).type()).isEqualTo(NotificationType.RESERVATION_EXPIRED);
        assertThat(listed.get(0).message()).isEqualTo("Your reservation 202 expired before payment completed.");
        assertThat(listed.get(0).reservationId()).isEqualTo(202L);
        assertThat(listed.get(0).recipientCustomerId()).isEqualTo(99L);
        assertThat(listed.get(1).message()).isEqualTo("Your reservation 201 is confirmed. Seats 10 are yours.");
    }

    @Test
    void listForCustomerFallsBackToTheTypeWordingWhenThePayloadHasNoReadableMessage() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 99L, 201L, NotificationType.RESERVATION_CANCELLED, "{not-json")));

        List<NotificationResponse> listed = service.listForCustomer(99L, 50, null);

        assertThat(listed.get(0).message())
                .isEqualTo("Your reservation 201 was cancelled because the payment was declined.");
    }

    @Test
    void listForCustomerWithNothingRecordedIsAnEmptyList() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(404L), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.listForCustomer(404L, 50, null)).isEmpty();
    }

    // ---- Bounded pages ----------------------------------------------------------------------------
    //
    // An inbox has no natural end, so the page size is the only thing standing between one
    // request and the size of a Customer's whole history. These pin the bound and the
    // cursor; the arithmetic across a real boundary is NotificationFlowBootTests's job,
    // against a real database with real timestamps.

    @Test
    void anOverLargeLimitIsClampedToTheConfiguredMaximum() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of());

        service.listForCustomer(99L, 100_000, null);

        // The bound the query actually runs at, not the one the caller asked for: a caller
        // must not be able to ask its way to an unbounded read.
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(notifications).findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(MAX_PAGE_SIZE);
    }

    @Test
    void aLimitWithinTheMaximumIsUsedAsAsked() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of());

        service.listForCustomer(99L, 7, null);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(notifications).findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void aNonsensicalLimitBecomesASingleRowRatherThanAnUnboundedRead() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of());

        service.listForCustomer(99L, -5, null);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(notifications).findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void aClampedLimitAlsoBoundsACursoredPage() {
        String cursor = service.cursorAfter(
                new NotificationResponse(5L, 50L, 99L, NotificationType.RESERVATION_CONFIRMED,
                        "m", Instant.parse("2026-09-25T10:15:30Z")));

        service.listForCustomer(99L, 100_000, cursor);

        verify(notifications).findPageAfter(99L, Instant.parse("2026-09-25T10:15:30Z"), 5L, MAX_PAGE_SIZE);
    }

    @Test
    void aCursorPagesTheDatabaseRatherThanTheWholeInbox() {
        String cursor = service.cursorAfter(
                new NotificationResponse(5L, 50L, 99L, NotificationType.RESERVATION_CONFIRMED,
                        "m", Instant.parse("2026-09-25T10:15:30Z")));

        service.listForCustomer(99L, 3, cursor);

        // The whole-inbox read is not the fallback for a cursored request: a cursor means
        // the client already holds the head of the list, so re-reading it unfiltered would
        // hand back rows it has already seen.
        verify(notifications, never())
                .findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class));
        verify(notifications).findPageAfter(99L, Instant.parse("2026-09-25T10:15:30Z"), 5L, 3);
    }

    @Test
    void aBlankCursorIsTheNewestPageNotACursoredOne() {
        when(notifications.findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class)))
                .thenReturn(List.of());

        service.listForCustomer(99L, 10, "  ");

        // An empty query parameter has to mean "no cursor", or a client whose cursor was
        // lost would get a 400 instead of the top of the inbox.
        verify(notifications).findByRecipientCustomerIdOrderBySentAtDescIdDesc(eq(99L), any(Pageable.class));
    }

    @Test
    void aCursorRoundTripsTheFullInstantAndStaysOpaque() {
        // Microsecond precision: the cursor must carry the whole instant, not a truncated
        // one, or resuming lands between two rows that shared a millisecond.
        Instant sentAt = Instant.parse("2026-09-25T10:15:30.123456Z");
        String cursor = service.cursorAfter(
                new NotificationResponse(7L, 50L, 99L, NotificationType.RESERVATION_EXPIRED, "m", sentAt));

        // Opaque: no structure a client could come to depend on, nothing that needs
        // escaping to sit in a query parameter, and nothing in the standard base64
        // alphabet's +, / or = that would have to be encoded again on the way out.
        assertThat(cursor).doesNotContain(":").matches("[A-Za-z0-9_-]+");

        service.listForCustomer(99L, 1, cursor);
        verify(notifications).findPageAfter(99L, sentAt, 7L, 1);
    }

    @Test
    void aCursorIsAPositionNotAGrant() {
        // It names a row, and the row is looked up within the customerId given in the same
        // request, so a cursor issued for one Customer cannot make the query read another's
        // rows — it can only ever come back empty.
        when(notifications.findPageAfter(404L, Instant.parse("2026-09-25T10:15:30Z"), 5L, 10))
                .thenReturn(List.of());

        String issuedForCustomer99 = service.cursorAfter(
                new NotificationResponse(5L, 50L, 99L, NotificationType.RESERVATION_CONFIRMED,
                        "m", Instant.parse("2026-09-25T10:15:30Z")));

        assertThat(service.listForCustomer(404L, 10, issuedForCustomer99)).isEmpty();
    }

    @Test
    void aCorruptCursorIsRejectedRatherThanPagingFromSomewhereArbitrary() {
        for (String corrupt : new String[] {
            "not base64 at all !!",
            "bm90LWEtY3Vyc29y", // valid base64, but not a position
            "MTIz",             // an instant with no id
            "MTIzOg",           // a trailing separator with no id
            "OnBoc3RvcnM",      // no instant
            "MTAwMDpk",         // an id of zero
        }) {
            assertThatThrownBy(() -> service.listForCustomer(99L, 10, corrupt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cursor");
        }
    }

    @Test
    void aCursorCannotBeTakenFromARowThatWasNeverReadBack() {
        // sent_at is a database default, so a row that was inserted and not re-read has no
        // position in the order to record.
        assertThatThrownBy(() -> service.cursorAfter(
                        new NotificationResponse(5L, 50L, 99L, NotificationType.RESERVATION_CONFIRMED, "m", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sentAt");
    }

    private NotificationEntity row(
            Long id, long customerId, long reservationId, NotificationType type, String payload) {
        return row(id, customerId, reservationId, type, payload, Instant.parse("2026-09-25T10:15:30Z"));
    }

    private NotificationEntity row(
            Long id,
            long customerId,
            long reservationId,
            NotificationType type,
            String payload,
            Instant sentAt) {
        NotificationEntity row = new NotificationEntity();
        ReflectionTestUtils.setField(row, "id", id);
        ReflectionTestUtils.setField(row, "recipientCustomerId", customerId);
        ReflectionTestUtils.setField(row, "reservationId", reservationId);
        ReflectionTestUtils.setField(row, "type", type);
        ReflectionTestUtils.setField(row, "payload", payload);
        ReflectionTestUtils.setField(row, "sentAt", sentAt);
        return row;
    }

    private EventEnvelope<JsonNode> envelope(
            UUID eventId, String eventType, long reservationId, long customerId, List<Long> seatIds, int amountCents) {
        return envelope(eventId, eventType, reservationId, customerId, seatIds, amountCents, null);
    }

    /** The same payload with one required field dropped, to prove each one is checked. */
    private EventEnvelope<JsonNode> envelope(
            UUID eventId,
            String eventType,
            long reservationId,
            long customerId,
            List<Long> seatIds,
            int amountCents,
            String omittedField) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reservationId", reservationId);
        fields.put("customerId", customerId);
        fields.put("eventId", 7L);
        fields.put("seatIds", seatIds);
        fields.put("amountCents", amountCents);
        fields.remove(omittedField);
        return new EventEnvelope<>(
                eventId, eventType, Instant.now(), "cid", new UUID(0L, reservationId), objectMapper.valueToTree(fields));
    }

    private JsonNode payload(long reservationId, long customerId, List<Long> seatIds, int amountCents) {
        return objectMapper.valueToTree(new JpaNotificationService.ReservationTerminalPayload(
                reservationId, customerId, 7L, seatIds, amountCents));
    }
}

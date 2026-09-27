package com.raydans.notificationservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.notificationservice.notification.NotificationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class ReservationTerminalConsumerTest {

    @Mock
    NotificationService notifications;

    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** The correlation id the consumer had in scope for each delegated envelope. */
    final List<String> correlationIdsSeen = new ArrayList<>();

    ReservationTerminalConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReservationTerminalConsumer(notifications, objectMapper);
        // Lenient because two tests deliberately never reach the service: one whose
        // record cannot be parsed, and one that replaces this answer with its own.
        Mockito.lenient()
                .doAnswer(invocation -> {
                    correlationIdsSeen.add(MDC.get(CorrelationIdFilter.MDC_KEY));
                    return null;
                })
                .when(notifications)
                .record(any());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void kafkaCorrelationHeaderWinsOverTheEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record = record(envelope("envelope-cid", 101L));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, "header-cid".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertThat(correlationIdsSeen).containsExactly("header-cid");
    }

    @Test
    void envelopeCorrelationIdIsUsedWhenHeaderIsAbsent() throws Exception {
        consumer.onMessage(record(envelope("envelope-cid", 102L)));

        assertThat(correlationIdsSeen).containsExactly("envelope-cid");
    }

    @Test
    void blankKafkaHeaderFallsBackToEnvelopeId() throws Exception {
        ConsumerRecord<String, String> record = record(envelope("envelope-cid", 103L));
        record.headers().add(CorrelationIdFilter.HEADER_NAME, " ".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record);

        assertThat(correlationIdsSeen).containsExactly("envelope-cid");
    }

    @Test
    void bothAbsentMintsARandomUuidSoTheSimulatedSendAlwaysHasATraceId() throws Exception {
        consumer.onMessage(record(envelope(null, 104L)));

        assertThat(correlationIdsSeen).hasSize(1);
        String minted = correlationIdsSeen.get(0);
        assertThat(minted).isNotBlank();
        assertThat(UUID.fromString(minted)).isNotNull();
    }

    @Test
    void theMdcIsClearedAfterEveryRecordSoCorrelationIdsNeverLeakBetweenDeliveries() throws Exception {
        consumer.onMessage(record(envelope("envelope-cid", 105L)));

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void aFailedRecordStillClearsTheMdcBeforeTheRetry() throws Exception {
        doAnswer(invocation -> {
                    throw new IllegalStateException("boom");
                })
                .when(notifications)
                .record(any());

        assertThatThrownBy(() -> consumer.onMessage(record(envelope("envelope-cid", 106L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void malformedJsonThrowsSoTheRecordCanBeRetriedAndDeadLettered() {
        assertThatThrownBy(() -> consumer.onMessage(record("{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialize");
        verify(notifications, never()).record(any());
    }

    private String envelope(String correlationId, long reservationId) throws com.fasterxml.jackson.core.JsonProcessingException {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                "reservation.ReservationConfirmed",
                Instant.now(),
                correlationId,
                new UUID(0L, reservationId),
                Map.of(
                        "reservationId", reservationId,
                        "customerId", 99L,
                        "eventId", 7L,
                        "seatIds", List.of(10L),
                        "amountCents", 45000));
        return objectMapper.writeValueAsString(envelope);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("reservation.events.v1", 0, 0L, "key", value);
    }
}

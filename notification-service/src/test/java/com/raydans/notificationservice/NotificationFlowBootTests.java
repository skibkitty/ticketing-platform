package com.raydans.notificationservice;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raydans.common.event.EventEnvelope;
import com.raydans.common.web.CorrelationIdFilter;
import com.raydans.notificationservice.notification.NotificationResponse;
import com.raydans.notificationservice.notification.NotificationService;
import com.raydans.notificationservice.notification.NotificationType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The saga's customer-facing leg over the real wire: terminal reservation events on
 * {@code reservation.events.v1} in, one Notification row and one simulated send out,
 * and the read surface that shows a Customer what they were told.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(NotificationFlowBootTests.TopicOwner.class)
class NotificationFlowBootTests {

    static final String RESERVATION_EVENTS_TOPIC = "reservation.events.v1";
    static final String DEAD_LETTER_TOPIC = RESERVATION_EVENTS_TOPIC + ".dlt";

    static final String CONFIRMED = "reservation.ReservationConfirmed";
    static final String CANCELLED = "reservation.ReservationCancelled";
    static final String EXPIRED = "reservation.ReservationExpired";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    NotificationService notifications;

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /**
     * The test provisions the topic it consumes, because in production it does not.
     *
     * <p>reservation-service produces {@code reservation.events.v1} and owns its topology;
     * this service consumes it and deliberately declares nothing (see
     * {@code notificationTopicOwnershipIsNotThisServicesToDecide}). The test therefore has
     * to stand in for the owner that is not running here, or the broker's own
     * auto-creation would decide the partition count and the test would prove nothing
     * about the real topology.
     */
    @TestConfiguration
    static class TopicOwner {

        @Bean
        KafkaAdmin.NewTopics testOwnedTopics() {
            return new KafkaAdmin.NewTopics(
                    new NewTopic(RESERVATION_EVENTS_TOPIC, 1, (short) 1),
                    new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1));
        }
    }

    @Test
    void notificationTopicOwnershipIsNotThisServicesToDecide() {
        // The regression this whole test class's setup depends on: if this service ever
        // starts declaring the shared topic again, whichever service booted first would be
        // silently deciding the partition and replication count for payment-service too,
        // and startup order would be the only thing setting the topology.
        assertThat(context.getBeansOfType(KafkaAdmin.NewTopics.class))
                .as("notification-service must not provision shared topic topology; "
                        + "reservation-service owns %s as its producer",
                        RESERVATION_EVENTS_TOPIC)
                .containsOnlyKeys("testOwnedTopics");
    }

    @Test
    void eachTerminalEventRecordsOneNotificationAndTheListSurfaceShowsThemNewestFirst() throws Exception {
        long customerId = 901L;
        produce(CONFIRMED, UUID.randomUUID(), 501L, customerId, List.of(10L, 11L), 45000);
        produce(CANCELLED, UUID.randomUUID(), 502L, customerId, List.of(12L), 15000);
        produce(EXPIRED, UUID.randomUUID(), 503L, customerId, List.of(), 0);

        awaitNotificationCount(customerId, 3);

        assertThat(notificationCount(501L)).isEqualTo(1);
        assertThat(notificationType(501L)).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(notificationRecipient(501L)).isEqualTo(customerId);
        assertThat(notificationMessage(501L)).contains("Your reservation 501 is confirmed.");
        assertThat(notificationMessage(501L)).contains("Seats 10, 11 are yours.");

        assertThat(notificationCount(502L)).isEqualTo(1);
        assertThat(notificationType(502L)).isEqualTo("RESERVATION_CANCELLED");
        assertThat(notificationMessage(502L)).contains("was cancelled because the payment was declined");

        assertThat(notificationCount(503L)).isEqualTo(1);
        assertThat(notificationType(503L)).isEqualTo("RESERVATION_EXPIRED");
        assertThat(notificationMessage(503L)).contains("expired before payment completed");

        // Every row records a send time; the column is DB-owned so it is never null.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification.notifications "
                                + "WHERE recipient_customer_id = ? AND sent_at IS NOT NULL",
                        Integer.class, customerId))
                .isEqualTo(3);

        // A different Customer's terminal event, consumed before the list is read, so
        // the absence of theirs from this list is a fact and not a timing accident.
        long otherCustomerId = 904L;
        produce(CONFIRMED, UUID.randomUUID(), 504L, otherCustomerId, List.of(20L), 20000);
        awaitNotificationCount(otherCustomerId, 1);

        ResponseEntity<List> listed =
                rest.getForEntity("/api/v1/notifications?customerId=" + customerId, List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(3);
        List<Map<String, Object>> rows = rowsOf(listed);
        assertThat(rows.stream().map(row -> longValue(row, "reservationId")).toList())
                .as("newest first")
                .containsExactly(503L, 502L, 501L);
        assertThat(rows.stream().map(row -> row.get("type")).toList())
                .containsExactly("RESERVATION_EXPIRED", "RESERVATION_CANCELLED", "RESERVATION_CONFIRMED");
        assertThat(rows.stream().map(row -> longValue(row, "recipientCustomerId")).toList())
                .containsOnly(customerId);

        Map<String, Object> newest = rows.get(0);
        assertThat((String) newest.get("message")).contains("Your reservation 503 expired");
        // A real client reads an ISO-8601 instant, not an epoch number.
        assertThat(Instant.parse((String) newest.get("sentAt"))).isNotNull();
    }

    @Test
    void theSimulatedSendIsLoggedOnceTheRowIsCommitted() throws Exception {
        long reservationId = 511L;
        long customerId = 911L;
        // The service that simulates the send is package-private (as in payment-service),
        // so its logger is named rather than imported.
        Logger serviceLogger =
                (Logger) LoggerFactory.getLogger("com.raydans.notificationservice.notification.JpaNotificationService");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            produce(CONFIRMED, UUID.randomUUID(), reservationId, customerId, List.of(10L), 15000, "corr-send-511");

            awaitNotification(reservationId);
            // The row is only visible once the transaction has committed, and the send is
            // logged before that commit — so the row landing means the log is already there.
            List<ILoggingEvent> sends = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("Simulated send"))
                    .filter(event -> event.getFormattedMessage().contains(String.valueOf(reservationId)))
                    .toList();
            assertThat(sends).hasSize(1);
            assertThat(sends.get(0).getLevel().toString()).isEqualTo("INFO");
            assertThat(sends.get(0).getFormattedMessage())
                    .contains("Simulated send")
                    .contains("RESERVATION_CONFIRMED")
                    .contains(String.valueOf(customerId))
                    .contains("Your reservation 511 is confirmed.");
            assertThat(sends.get(0).getMDCPropertyMap())
                    .as("the simulated send is traceable back to the request that caused it")
                    .containsEntry(CorrelationIdFilter.MDC_KEY, "corr-send-511");
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void duplicateDeliveryOfTheSameEventRecordsOneNotificationAndIsNeverDeadLettered() throws Exception {
        // Positive control first: the dead-letter path works at all in this run, so the
        // "never dead-lettered" assertion at the end of this test is not vacuous.
        UUID poisonEventId = UUID.randomUUID();
        produce(CANCELLED, poisonEventId, 520L, 0L, List.of(30L), 15000);
        assertThat(awaitOnDeadLetterTopic(record -> record.value().contains(poisonEventId.toString())))
                .as("a record with no recipient should be dead-lettered")
                .isTrue();

        UUID duplicatedEventId = UUID.randomUUID();
        long duplicatedReservationId = 521L;
        // Three records pinned to the same partition, so the sentinel is strictly ordered
        // behind both copies of the duplicate. Its Notification row is therefore proof
        // that the consumer drained the duplicates too — no sleeping on a guess.
        produce(CONFIRMED, duplicatedEventId, duplicatedReservationId, 922L, List.of(40L), 15000);
        produce(CONFIRMED, duplicatedEventId, duplicatedReservationId, 922L, List.of(40L), 15000);
        produce(CONFIRMED, UUID.randomUUID(), 599L, 922L, List.of(99L), 9900);

        awaitNotification(599L);

        assertThat(notificationCount(duplicatedReservationId))
                .as("a duplicate delivery must not tell the customer twice")
                .isEqualTo(1);
        assertThat(notificationType(duplicatedReservationId)).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(processedCount(duplicatedEventId))
                .as("the event is recorded as processed exactly once")
                .isEqualTo(1);

        // Retries run FixedBackOff(1000ms, 5) before the DLT, so wait longer than that
        // before declaring a duplicate was never classified as poison.
        assertThat(countOnDeadLetterTopicWithin(Duration.ofSeconds(12),
                        record -> record.value().contains(duplicatedEventId.toString())))
                .as("an idempotency duplicate must never be classified as poison")
                .isZero();
    }

    @Test
    void aDifferentEventIdForATransitionAlreadyToldAboutIsStillOneNotification() throws Exception {
        // The repeat processed_events cannot catch. Dedupe on eventId only stops the SAME
        // event arriving twice; this is a fresh event id saying something the Customer has
        // already been told about one Reservation, so only the (reservation_id, type) claim
        // stands between it and a second notification. Proven here against the real unique
        // index — a mocked repository cannot tell us the conflict resolves as a no-op
        // rather than an integrity failure (ADR 010).
        UUID poisonEventId = UUID.randomUUID();
        produce(CANCELLED, poisonEventId, 523L, 0L, List.of(31L), 15000);
        assertThat(awaitOnDeadLetterTopic(record -> record.value().contains(poisonEventId.toString())))
                .as("positive control: the dead-letter path works in this run")
                .isTrue();

        long reservationId = 524L;
        long customerId = 924L;
        produce(CONFIRMED, UUID.randomUUID(), reservationId, customerId, List.of(41L), 15000);

        // The repeat: same Reservation, same terminal type, brand new event id.
        UUID repeatedEventId = UUID.randomUUID();
        produce(CONFIRMED, repeatedEventId, reservationId, customerId, List.of(41L), 15000);

        // A sentinel on the same partition, ordered behind the repeat, so its Notification
        // row proves the repeat was consumed before the counts below are read. Without it
        // "one row" could just mean "not processed yet".
        produce(CONFIRMED, UUID.randomUUID(), 598L, customerId, List.of(98L), 9800);
        awaitNotification(598L);

        assertThat(notificationCount(reservationId))
                .as("a differently ID'd repeat must not tell the customer twice")
                .isEqualTo(1);
        assertThat(processedCount(repeatedEventId))
                .as("the repeat is still recorded as processed, so it is not re-examined")
                .isEqualTo(1);

        // Without ON CONFLICT DO NOTHING the second insert would raise an integrity
        // exception, and the retry policy would end up quarantining a valid event. This is
        // the assertion that makes the claim a no-op rather than a poison path.
        assertThat(countOnDeadLetterTopicWithin(Duration.ofSeconds(12),
                        record -> record.value().contains(repeatedEventId.toString())))
                .as("a repeat the customer was already told about must not be classified as poison")
                .isZero();
    }

    @Test
    void malformedTerminalEventIsDeadLetteredAndTheConsumerKeepsProcessing() throws Exception {
        // A supported event type whose payload is undeliverable: there is no Customer to
        // send it to, so it must be quarantined rather than silently recorded or dropped.
        UUID poisonEventId = UUID.randomUUID();
        produce(CONFIRMED, poisonEventId, 530L, 0L, List.of(50L), 15000);

        assertThat(awaitOnDeadLetterTopic(record -> record.value().contains(poisonEventId.toString())))
                .as("a terminal event with no recipient should be dead-lettered")
                .isTrue();
        assertThat(notificationCount(530L))
                .as("no notification for a poisoned event")
                .isZero();
        assertThat(processedCount(poisonEventId))
                .as("no processed row for a poisoned event (the claim rolled back)")
                .isZero();

        // The consumer is not wedged: a subsequent valid event is still processed.
        produce(EXPIRED, UUID.randomUUID(), 531L, 931L, List.of(51L), 15000);
        awaitNotification(531L);
        assertThat(notificationType(531L)).isEqualTo("RESERVATION_EXPIRED");
    }

    @Test
    void unparseableRecordIsDeadLetteredAndTheConsumerKeepsProcessing() throws Exception {
        kafka.send(new ProducerRecord<>(RESERVATION_EVENTS_TOPIC, 0, "poison-key", "{not-json"))
                .get(10, TimeUnit.SECONDS);

        assertThat(awaitOnDeadLetterTopic(
                        record -> "poison-key".equals(record.key()) && "{not-json".equals(record.value())))
                .as("a record that cannot be parsed should be dead-lettered")
                .isTrue();

        produce(CANCELLED, UUID.randomUUID(), 540L, 941L, List.of(60L), 15000);
        awaitNotification(540L);
    }

    @Test
    void expiryThatReleasedNoSeatsStillNotifiesTheCustomer() throws Exception {
        // The upstream contract allows this: an expiry publishes the seats a transition
        // actually released, which is none when a hold was re-extended first. Quarantining
        // it would deny the customer the one notification that explains their hold lapsing.
        long reservationId = 550L;
        long customerId = 951L;
        produce(EXPIRED, UUID.randomUUID(), reservationId, customerId, List.of(), 0);

        awaitNotification(reservationId);

        assertThat(notificationCount(reservationId)).isEqualTo(1);
        assertThat(notificationMessage(reservationId))
                .isEqualTo("Your reservation 550 expired before payment completed.");
    }

    @Test
    void concurrentDeliveryOfTheSameEventRecordsOneNotification() throws Exception {
        long reservationId = 560L;
        long customerId = 961L;
        UUID eventId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = terminalEnvelope(
                eventId, CONFIRMED, reservationId, customerId, List.of(70L), 15000);

        // The listener is single-threaded, so the true race lives here: several
        // transactions racing to claim the same event through the same service bean.
        runConcurrently(6, () -> notifications.record(envelope));

        assertThat(notificationCount(reservationId)).isEqualTo(1);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    void aNonPositiveCustomerIdIsRejectedWith400RatherThanQueryingForNobody() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/notifications?customerId=0", Map.class);

        // Also proves the constraint violation is not swallowed by the catch-all advice,
        // which would turn a bad request into a 500.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(response.getBody()).containsEntry("path", "/api/v1/notifications");
    }

    @Test
    void aCustomerWithNothingRecordedGetsAnEmptyList() {
        ResponseEntity<List> response = rest.getForEntity("/api/v1/notifications?customerId=997", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void pagesThroughTheWholeInboxWithoutRepeatingOrSkippingANotification() throws Exception {
        long customerId = 903L;
        // Six Notifications, so a limit of 2 needs three pages and the last page is exact.
        for (int i = 0; i < 6; i++) {
            produce(CONFIRMED, UUID.randomUUID(), 600L + i, customerId, List.of(10L), 45000);
        }
        awaitNotificationCount(customerId, 6);

        List<Long> walked = new ArrayList<>();
        String cursor = null;
        for (int pages = 0; pages < 10; pages++) {
            ResponseEntity<List> page = page(customerId, 2, cursor);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<?> body = page.getBody();
            assertThat(body).isNotNull().hasSizeLessThanOrEqualTo(2);
            if (body.isEmpty()) {
                break;
            }
            walked.addAll(idsOf(body));
            cursor = notifications.cursorAfter(responseOf(body.get(body.size() - 1)));
            if (body.size() < 2) {
                break;
            }
        }

        // Every row exactly once: no repeat (which an offset page would cause the moment
        // anything new arrived) and nothing missing (which a mis-keyed cursor would cause).
        assertThat(walked).hasSize(6).doesNotHaveDuplicates();
        assertThat(walked).containsExactlyInAnyOrderElementsOf(
                jdbc.queryForList(
                        "SELECT id FROM notification.notifications WHERE recipient_customer_id = ? ORDER BY sent_at DESC, id DESC",
                        Long.class, customerId));
    }

    @Test
    void aNotificationArrivingBetweenPagesDoesNotShiftThePageUnderTheClient() throws Exception {
        long customerId = 904L;
        for (int i = 0; i < 4; i++) {
            produce(CONFIRMED, UUID.randomUUID(), 700L + i, customerId, List.of(10L), 45000);
        }
        awaitNotificationCount(customerId, 4);

        // Read the first page, then let something new arrive at the head of the order.
        ResponseEntity<List> first = page(customerId, 2, null);
        assertThat(first.getBody()).hasSize(2);
        String cursor = notifications.cursorAfter(
                responseOf(first.getBody().get(first.getBody().size() - 1)));

        produce(CONFIRMED, UUID.randomUUID(), 799L, customerId, List.of(10L), 45000);
        awaitNotificationCount(customerId, 5);

        // The reason this is a keyset and not an offset: the row the cursor names is still
        // the boundary, so the second page resumes exactly where the first stopped. With
        // OFFSET 2 the new row at the head would push everything down one and the client
        // would be handed a row it has already read, and never reach the oldest.
        ResponseEntity<List> second = page(customerId, 2, cursor);
        List<Long> secondIds = idsOf(second.getBody());

        assertThat(idsOf(first.getBody())).doesNotContainAnyElementsOf(secondIds);
        assertThat(secondIds).hasSize(2);
    }

    @Test
    void notificationsSharingOneSentTimestampAreEachReturnedExactlyOnce() throws Exception {
        long customerId = 905L;
        // Three rows forced to share a sent_at: the timestamp is a database default, so
        // backdating it in the database is the only way to construct the collision. This is
        // the case the id tiebreak exists for -- a cursor carrying only a timestamp would
        // either repeat these or skip them.
        Instant shared = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 3; i++) {
            produce(CONFIRMED, UUID.randomUUID(), 800L + i, customerId, List.of(10L), 45000);
        }
        awaitNotificationCount(customerId, 3);
        jdbc.update(
                "UPDATE notification.notifications SET sent_at = ? WHERE recipient_customer_id = ?",
                Timestamp.from(shared), customerId);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT sent_at) FROM notification.notifications WHERE recipient_customer_id = ?",
                Integer.class, customerId)).isEqualTo(1);

        List<Long> walked = new ArrayList<>();
        String cursor = null;
        for (int pages = 0; pages < 5; pages++) {
            ResponseEntity<List> current = page(customerId, 1, cursor);
            List<?> body = current.getBody();
            if (body == null || body.isEmpty()) {
                break;
            }
            walked.addAll(idsOf(body));
            cursor = notifications.cursorAfter(responseOf(body.get(body.size() - 1)));
        }

        assertThat(walked).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void aLimitBeyondTheCeilingIsClampedRatherThanHonoured() throws Exception {
        long customerId = 906L;
        produce(CONFIRMED, UUID.randomUUID(), 900L, customerId, List.of(10L), 45000);
        awaitNotificationCount(customerId, 1);

        // Over the max, the response is still a success -- the ask is bounded, not refused.
        ResponseEntity<List> response = rest.getForEntity(
                "/api/v1/notifications?customerId=" + customerId + "&limit=100000", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void anUnreadableCursorIs400() throws Exception {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/notifications?customerId=907&cursor=not-a-cursor", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).contains("cursor");
    }

    private ResponseEntity<List> page(long customerId, int limit, String cursor) {
        String url = "/api/v1/notifications?customerId=" + customerId + "&limit=" + limit
                + (cursor == null ? "" : "&cursor=" + cursor);
        return rest.getForEntity(url, List.class);
    }

    private List<Long> idsOf(List<?> body) {
        return body.stream()
                .map(row -> ((Number) ((Map<?, ?>) row).get("id")).longValue())
                .toList();
    }

    private NotificationResponse responseOf(Object row) {
        Map<?, ?> map = (Map<?, ?>) row;
        return new NotificationResponse(
                ((Number) map.get("id")).longValue(),
                ((Number) map.get("reservationId")).longValue(),
                ((Number) map.get("recipientCustomerId")).longValue(),
                NotificationType.valueOf((String) map.get("type")),
                (String) map.get("message"),
                Instant.parse((String) map.get("sentAt")));
    }

    private void produce(String eventType, UUID eventId, long reservationId, long customerId, List<Long> seatIds,
            int amountCents) throws Exception {
        produce(eventType, eventId, reservationId, customerId, seatIds, amountCents, "corr-" + reservationId);
    }

    /**
     * Publishes one envelope, pinned to partition 0 so that the ordering this test
     * relies on between records is the partition's, not the producer's timing.
     */
    private void produce(String eventType, UUID eventId, long reservationId, long customerId, List<Long> seatIds,
            int amountCents, String correlationId) throws Exception {
        String value = objectMapper.writeValueAsString(
                terminalEnvelope(eventId, eventType, reservationId, customerId, seatIds, amountCents));
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RESERVATION_EVENTS_TOPIC, 0, new UUID(0L, reservationId).toString(), value);
        record.headers().add(CorrelationIdFilter.HEADER_NAME, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private EventEnvelope<JsonNode> terminalEnvelope(
            UUID eventId, String eventType, long reservationId, long customerId, List<Long> seatIds, int amountCents) {
        return new EventEnvelope<>(
                eventId,
                eventType,
                Instant.now(),
                "corr-" + reservationId,
                new UUID(0L, reservationId),
                objectMapper.valueToTree(Map.of(
                        "reservationId", reservationId,
                        "customerId", customerId,
                        "eventId", 7L,
                        "seatIds", seatIds,
                        "amountCents", amountCents)));
    }

    /** The read surface returns a JSON array; re-typed for readable assertions. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(ResponseEntity<List> response) {
        return (List<Map<String, Object>>) (List<?>) response.getBody();
    }

    /**
     * An untyped JSON number is whatever width Jackson chose — Integer for these ids, not
     * Long — so compare by value rather than by boxed type.
     */
    private long longValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** Awaits a Customer's whole inbox reaching its expected size. */
    private void awaitNotificationCount(long customerId, int expected) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (notificationCountFor(customerId) >= expected) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError(
                "Customer " + customerId + " had " + notificationCountFor(customerId) + " notification(s), "
                        + "expected " + expected + " within 30s");
    }

    /**
     * Awaits the one Notification a Reservation owes. Scoped by Reservation, not Customer, so a
     * caller cannot pass the wrong id: a Reservation that was notified is the fact these
     * assertions are about, and the inbox size is asserted separately where it matters.
     */
    private void awaitNotification(long reservationId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (notificationCount(reservationId) > 0) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError(
                "Reservation " + reservationId + " had " + notificationCount(reservationId)
                        + " notification(s), expected 1 within 30s");
    }

    private int notificationCountFor(long customerId) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notification.notifications WHERE recipient_customer_id = ?",
                Integer.class, customerId);
        return rows == null ? 0 : rows;
    }

    private int notificationCount(long reservationId) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notification.notifications WHERE reservation_id = ?",
                Integer.class, reservationId);
        return rows == null ? 0 : rows;
    }

    private String notificationType(long reservationId) {
        return jdbc.queryForObject(
                "SELECT type FROM notification.notifications WHERE reservation_id = ?", String.class, reservationId);
    }

    private long notificationRecipient(long reservationId) {
        Long recipient = jdbc.queryForObject(
                "SELECT recipient_customer_id FROM notification.notifications WHERE reservation_id = ?",
                Long.class, reservationId);
        return recipient == null ? -1 : recipient;
    }

    private String notificationMessage(long reservationId) {
        return jdbc.queryForObject(
                "SELECT payload ->> 'message' FROM notification.notifications WHERE reservation_id = ?",
                String.class, reservationId);
    }

    private int processedCount(UUID eventId) {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notification.processed_events WHERE event_id = ?", Integer.class, eventId);
        return rows == null ? 0 : rows;
    }

    /**
     * True as soon as a matching record is on the dead-letter topic, so a positive control
     * costs the event's own latency rather than the whole window. Its negative counterpart
     * must still burn the window in full.
     */
    private boolean awaitOnDeadLetterTopic(Predicate<ConsumerRecord<String, String>> match) {
        Instant deadline = Instant.now().plusSeconds(30);
        try (KafkaConsumer<String, String> consumer = newConsumer(DEAD_LETTER_TOPIC)) {
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(1)).records(DEAD_LETTER_TOPIC)) {
                    if (match.test(record)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private long countOnDeadLetterTopicWithin(
            Duration window, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = newConsumer(DEAD_LETTER_TOPIC)) {
            Instant deadline = Instant.now().plus(window);
            long count = 0;
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(1)).records(DEAD_LETTER_TOPIC)) {
                    if (match.test(record)) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    private KafkaConsumer<String, String> newConsumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "boot-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("start barrier never released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted waiting for start barrier", ex);
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        try {
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads started").isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures)
                .as("no duplicate delivery may throw (idempotency is a no-op, not an error)")
                .isEmpty();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting async state", ex);
        }
    }
}

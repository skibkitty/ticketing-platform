package com.raydans.notificationservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;

/**
 * Self-provisions the topic this service consumes from and the DLT it
 * dead-letters to, through Spring's {@code KafkaAdmin} on startup, so
 * availability never depends on the broker having
 * {@code auto.create.topics.enable=true}.
 *
 * <p>This is a consumer-side concern here, not a producer one: the topic and
 * the DLT both have to exist before this service can do its job, and the DLT in
 * particular is this module's only quarantine path (ADR 008). The dead-letter
 * topic is created with the SAME partition/replication layout as the source, so
 * keyed ordering is preserved end-to-end.
 *
 * <p>{@code reservation.events.v1} is shared with payment-service and its layout is
 * reservation-service's to decide. Co-provisioning it is idempotent — a broker
 * applies {@code NewTopic} to a topic that already exists as a no-op — so the
 * declaration is a floor, not an override: whichever service starts first creates
 * the topic and the others agree with it.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.topics.reservation-events}")
    private String reservationEventsTopic;

    @Value("${app.kafka.topic-partitions:1}")
    private int partitions;

    @Value("${app.kafka.topic-replicas:1}")
    private short replicas;

    @Bean
    NewTopics notificationTopics() {
        return new NewTopics(
                new NewTopic(reservationEventsTopic, partitions, replicas),
                new NewTopic(reservationEventsTopic + ".dlt", partitions, replicas));
    }
}

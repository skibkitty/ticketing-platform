package com.raydans.reservationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Provisions {@code reservation.events.v1} — the topic this service PRODUCES — and its
 * dead-letter topic, through Spring's {@code KafkaAdmin} on startup, so availability never
 * depends on the broker having {@code auto.create.topics.enable=true}.
 *
 * <p>This service is the single owner of that topic's topology because it is the only
 * producer of it. Its consumers (payment-service, notification-service) deliberately do NOT
 * declare it: whichever service started first would otherwise decide the partition count
 * and replication factor for everyone, and that would be decided by startup order rather
 * than by whoever owns the data. One owner, one answer.
 *
 * <p>{@code payment.events.v1} is the mirror image — this service consumes it, and
 * declaring it is a consumer-side convenience, because payment-service has no topic
 * declaration of its own to inherit from.
 *
 * <p>The dead-letter topics (ADR 008) are {@code <source-topic>.dlt} and are created with
 * the SAME partition/replication layout as their source, so keyed ordering and failover
 * semantics are preserved end-to-end.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.topics.payment-outcome}")
    private String paymentOutcomeTopic;

    @Value("${app.kafka.topics.reservation-events}")
    private String reservationEventsTopic;

    @Value("${app.kafka.topic-partitions:1}")
    private int partitions;

    @Value("${app.kafka.topic-replicas:1}")
    private short replicas;

    @Bean
    NewTopics paymentTopics() {
        return new NewTopics(
                new NewTopic(paymentOutcomeTopic, partitions, replicas),
                new NewTopic(paymentOutcomeTopic + ".dlt", partitions, replicas));
    }

    /** The topics this service owns as their producer. */
    @Bean
    NewTopics reservationEventTopics() {
        return new NewTopics(
                new NewTopic(reservationEventsTopic, partitions, replicas),
                new NewTopic(reservationEventsTopic + ".dlt", partitions, replicas));
    }
}
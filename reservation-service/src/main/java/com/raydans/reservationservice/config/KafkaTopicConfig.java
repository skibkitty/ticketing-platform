package com.raydans.reservationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Self-provisions the topics this service consumes from (and the DLT it
 * dead-letters to) through Spring's {@code KafkaAdmin} on startup, so availability
 * never depends on the broker having {@code auto.create.topics.enable=true}.
 *
 * <p>The dead-letter topic (ADR 008) is {@code <source-topic>.dlt} and is created with
 * the SAME partition/replication layout as the source, so keyed ordering and
 * failover semantics are preserved end-to-end.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.topics.payment-outcome}")
    private String paymentOutcomeTopic;

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
}
package com.raydans.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The saga's fan-out leg: the only service that subscribes to the reservation's
 * terminal events and turns each of them into a customer-facing Notification.
 *
 * <p>It is deliberately the end of the chain. Nothing downstream of a
 * Notification exists, so unlike reservation-service and payment-service this
 * module runs no transactional outbox and no poller (see ADR 010): its only
 * inbound seam is the Kafka consumer, and its only outbound effects are the
 * Notification row and a simulated send written to the log.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

package com.raydans.notificationservice.web;

import com.raydans.notificationservice.notification.NotificationResponse;
import com.raydans.notificationservice.notification.NotificationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final int defaultPageSize;

    public NotificationController(
            NotificationService notifications,
            @Value("${app.notifications.default-page-size:50}") int defaultPageSize) {
        this.notifications = notifications;
        this.defaultPageSize = defaultPageSize;
    }

    /**
     * One page of a Customer's Notifications, newest first.
     *
     * <p>Paged because an inbox has no natural end: returning the whole history would make
     * one request cost a function of how long the Customer has existed, which is the kind
     * of cost that degrades without anyone changing a line of code. {@code limit} bounds
     * the page and {@code cursor} resumes past the last row of the previous one. An
     * over-large {@code limit} is clamped by the service to its maximum rather than
     * rejected, so a client cannot make the query unbounded either way.
     *
     * <p>Scoping is by explicit {@code customerId} rather than by the caller's
     * identity: this service is the trust boundary's downstream and holds no
     * authentication of its own. Whether the caller may read this Customer's row is the
     * gateway's to answer (ADR 002), and no gateway route exists yet — until one does,
     * anyone who can reach this port can read any Customer's inbox by id, so this
     * endpoint is not safe to expose outside the compose network as it stands.
     *
     * <p>The id and the cursor are checked by the service, not by bean-validation
     * constraints on these arguments: which exception a constraint violation raises here
     * depends on whether a {@code MethodValidationPostProcessor} is in play, and the
     * service owns these rules anyway — they are the same positive-id rule its inbound
     * payloads are held to.
     *
     * <p>The response stays a bare array. Paging is additive, so a client that asks for
     * nothing but {@code customerId} keeps reading a list, and the next page is one more
     * request away rather than a different response shape to parse.
     */
    @GetMapping
    List<NotificationResponse> listForCustomer(
            @RequestParam("customerId") long customerId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return notifications.listForCustomer(customerId, limit == null ? defaultPageSize : limit, cursor);
    }
}

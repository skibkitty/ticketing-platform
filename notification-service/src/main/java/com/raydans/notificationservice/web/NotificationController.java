package com.raydans.notificationservice.web;

import com.raydans.notificationservice.notification.NotificationResponse;
import com.raydans.notificationservice.notification.NotificationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * A Customer's Notifications, newest first.
     *
     * <p>Scoping is by explicit {@code customerId} rather than by the caller's
     * identity: this service is the trust boundary's downstream and holds no
     * authentication of its own. Whether the caller may read this Customer's row is the
     * gateway's to answer (ADR 002), and no gateway route exists yet — until one does,
     * anyone who can reach this port can read any Customer's inbox by id, so this
     * endpoint is not safe to expose outside the compose network as it stands.
     *
     * <p>The id is checked by the service, not by a bean-validation constraint on
     * this argument: which exception a constraint violation raises here depends on
     * whether a {@code MethodValidationPostProcessor} is in play, and the service
     * owns this rule anyway — it is the same positive-id rule its inbound payloads
     * are held to.
     */
    @GetMapping
    List<NotificationResponse> listForCustomer(@RequestParam("customerId") long customerId) {
        return notifications.listForCustomer(customerId);
    }
}

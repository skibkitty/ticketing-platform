package com.raydans.notificationservice.web;

import com.raydans.notificationservice.notification.NotificationPage;
import com.raydans.notificationservice.notification.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    /**
     * The header the gateway writes the verified caller's {@code Customer.id} into
     * (ADR 002), and the only thing this read is scoped by. Named here rather than
     * duplicated from the gateway so the two ends of that contract have one
     * spelling each and a rename breaks compilation instead of production.
     */
    public static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final NotificationService notifications;
    private final int defaultPageSize;

    public NotificationController(
            NotificationService notifications,
            @Value("${app.notifications.default-page-size:50}") int defaultPageSize) {
        this.notifications = notifications;
        this.defaultPageSize = defaultPageSize;
    }

    /**
     * One page of the calling Customer's own Notifications, newest first.
     *
     * <p>Scoped by {@link #CUSTOMER_HEADER} and by nothing else, so a caller reads
     * their inbox by being themselves rather than by naming a customer. A
     * {@code customerId} parameter used to be the only scope on this route, which
     * made reading anyone else's inbox a matter of changing a number: the gateway
     * authorizes any authenticated caller onto {@code /api/**} and had no view of
     * this route at all, so customer 42 read customer 43's notifications by asking.
     * See ADR 011.
     *
     * <p>That parameter is still accepted, and refused rather than honoured when it
     * disagrees with the verified header — 400, not 403, because nothing about the
     * caller's own permissions is wrong. It is redundant rather than dangerous, and
     * a client that sends the id it already proved is unaffected. Refusing only the
     * mismatch means a client that has quietly started believing it can choose whose
     * inbox it reads fails its tests, rather than receiving its own notifications
     * and reporting "support cannot see anything" weeks later.
     *
     * <p>With no header there is no read at all: {@code required = true} makes this
     * route unreachable for anything that did not come through the gateway, so a
     * process that can reach this port directly — host-side debugging, a future
     * internal service — cannot read any inbox by guessing an id.
     *
     * <p>Paged because an inbox has no natural end: returning the whole history would make
     * one request cost a function of how long the Customer has existed, which is the kind
     * of cost that degrades without anyone changing a line of code. {@code limit} bounds
     * the page and {@code cursor} resumes past the last row of the previous one. An
     * over-large {@code limit} is clamped by the service to its maximum rather than
     * rejected, so a client cannot make the query unbounded either way.
     *
     * <p>The id and the cursor are checked by the service, not by bean-validation
     * constraints on these arguments: which exception a constraint violation raises here
     * depends on whether a {@code MethodValidationPostProcessor} is in play, and the
     * service owns these rules anyway — they are the same positive-id rule its inbound
     * payloads are held to.
     *
     * <p>The response is a page and not a bare array, because the whole point of keyset
     * pagination is the position after the page and a client cannot page from a cursor it
     * was never handed. So the body is {@code {"items": [...], "nextCursor": "..."}}: a
     * reader that wants page two sends the {@code nextCursor} of page one straight back as
     * {@code cursor}, without knowing or caring how it is encoded. {@code nextCursor} is
     * {@code null} on the last page, so the end of the inbox is something the response
     * states rather than something a client infers from a page that happens to look full.
     *
     * <p>Only the scope changed: the parameters, the default and clamped page sizes and the
     * cursor's meaning are as they were. Keeping the array would have meant putting
     * {@code nextCursor} somewhere else — a header, a link — which is the same contract in
     * a shape a client has to be told about out of band.
     */
    @GetMapping("/api/v1/notifications")
    NotificationPage listMine(
            @RequestHeader(value = CUSTOMER_HEADER, required = true) long customerId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "customerId", required = false) Long claimedCustomerId) {
        if (claimedCustomerId != null && claimedCustomerId != customerId) {
            // 400 rather than 403: the caller is entitled to their own inbox and this
            // is not it, so the request contradicts itself rather than exceeding a
            // permission. The message names both ids, because "you may not read that"
            // would describe a rule that does not exist here.
            throw new IllegalArgumentException(
                    "customerId " + claimedCustomerId + " does not match the authenticated caller ("
                            + customerId + "). This route reads only the caller's own notifications; to read"
                            + " another Customer's, an administrator uses"
                            + " /api/v1/admin/customers/{customerId}/notifications.");
        }
        return notifications.listForCustomer(customerId, limit == null ? defaultPageSize : limit, cursor);
    }

    /**
     * One page of another Customer's Notifications, for the platform operator.
     *
     * <p>A separate route rather than a flag on the one above, and that is the decision
     * ADR 011 records: {@code /api/v1/notifications} means "yours" and keeps meaning only
     * that, so the self-service read can never be widened by accident and a log line says
     * which of the two was used. The alternative — one route and an {@code X-User-Roles}
     * check inside this service — would put an authorization decision in a service that
     * holds no authentication of its own, which is the boundary ADR 002 declines to
     * cross.
     *
     * <p>Nothing here decides who may call it. The gateway answers that, with
     * {@code ADMIN} as the only role that reaches {@code /api/v1/admin/**}, so this method
     * serves a Customer id it is handed and trusts the hop in front of it exactly as
     * {@code ReservationController} does. Reached without that hop it is an open door, which
     * is why the port is published to no host interface at all rather than only to the
     * ones a colleague is not on (ADR 012): loopback is still a socket any local process
     * can open, and this route is the one that answers "what did that customer see?".
     */
    @GetMapping("/api/v1/admin/customers/{customerId}/notifications")
    NotificationPage listForAnotherCustomer(
            @PathVariable("customerId") long customerId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return notifications.listForCustomer(customerId, limit == null ? defaultPageSize : limit, cursor);
    }
}

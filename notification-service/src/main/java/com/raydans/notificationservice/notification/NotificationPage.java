package com.raydans.notificationservice.notification;

import java.util.List;

/**
 * One page of a Customer's inbox, together with the position to resume from if there is
 * more of it.
 *
 * <p>The position is part of the answer rather than something the reader reconstructs from
 * the rows: a client that is not handed it has no supported way to ask for the next page,
 * which makes a keyset cursor the server keeps to itself no cursor at all. So the page
 * carries {@code nextCursor} — the exact position this service issued for the last row on
 * it — and a client pages by sending that value back as the {@code cursor} parameter. It
 * never has to know what is inside, which is what keeps the encoding free to change.
 *
 * <p>{@code nextCursor} is {@code null} when the page is the last one, so the end of an
 * inbox is stated by the response rather than inferred from a page that happens to look
 * full.
 *
 * <p>Lives beside the service for the same reason {@link NotificationResponse} does: it is
 * what the service returns, not a rendering of it, so the service layer stays independent
 * of the transport.
 */
public record NotificationPage(List<NotificationResponse> items, String nextCursor) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}

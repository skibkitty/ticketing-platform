package com.raydans.notificationservice.notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * An opaque position in a Customer's Notification inbox: the exact row a page ended on.
 *
 * <p>Carries {@code (sentAt, id)} rather than a row count. A count would be an offset, and
 * an offset is wrong for a list that grows at the head: a Notification that arrives between
 * two requests pushes everything down one place, so the client silently re-reads a row it
 * already has and never reaches one it has not. Naming the row itself cannot shift.
 *
 * <p>The {@code id} half is not decoration. {@code sent_at} is a database timestamp with
 * microsecond resolution, so a burst of Notifications landing together can share one, and
 * {@code sent_at} alone is not a position in the order. The pair is the only total order
 * the inbox has, so it is the only thing that can be resumed from.
 *
 * <p>Base64url so the value survives being a query parameter without escaping, and opaque
 * so a client cannot read structure into it and start depending on that — {@link #decode}
 * is the only thing that has to understand what it means, and it rejects anything it did
 * not issue.
 */
record NotificationCursor(Instant sentAt, long id) {

    private static final String SEPARATOR = ":";

    static NotificationCursor of(Instant sentAt, long id) {
        // sent_at is a database default, not an insertable column, so an entity that was
        // never read back can carry null here. There is no position to record in that case,
        // and inventing one would page from the wrong place.
        if (sentAt == null) {
            throw new IllegalArgumentException("cannot take a cursor for a notification with no sentAt");
        }
        if (id <= 0) {
            throw new IllegalArgumentException("cannot take a cursor for a notification with no id: " + id);
        }
        return new NotificationCursor(sentAt, id);
    }

    /**
     * The instant is carried as its full ISO-8601 form, not as epoch millis. Postgres
     * timestamps have microsecond resolution and millis are coarser, so a millisecond
     * cursor would round <em>down</em> — and a row sharing a millisecond with the cursor
     * but not its microsecond would then satisfy neither {@code sent_at < cursor} nor
     * {@code sent_at = cursor}, so it would be silently skipped by every page after it.
     */
    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((sentAt + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    static NotificationCursor decode(String cursor) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cursor is not a valid page position", ex);
        }
        // lastIndexOf, not indexOf: the ISO-8601 instant contains ':' itself, and the
        // separator is the one the id follows.
        int split = decoded.lastIndexOf(SEPARATOR);
        if (split <= 0 || split == decoded.length() - 1) {
            throw new IllegalArgumentException("cursor is not a valid page position: " + cursor);
        }
        Instant at;
        try {
            at = Instant.parse(decoded.substring(0, split));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("cursor is not a valid page position: " + cursor, ex);
        }
        long id;
        try {
            id = Long.parseLong(decoded.substring(split + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("cursor is not a valid page position: " + cursor, ex);
        }
        if (id <= 0) {
            throw new IllegalArgumentException("cursor is not a valid page position: " + cursor);
        }
        return new NotificationCursor(at, id);
    }
}

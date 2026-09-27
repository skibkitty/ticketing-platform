-- Notification schema (one instance, one schema per service — docs/adr/005).
-- Idempotent: compose's postgres-init.sql also creates the schema.
-- The *_at timestamps are DB-owned (DEFAULT now(), insertable=false in JPA):
-- entities never write them, so there is exactly one source of truth for sent_at.
CREATE SCHEMA IF NOT EXISTS notification;

SET search_path TO notification;

CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    recipient_customer_id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A Reservation reaches exactly one terminal state, so it owes exactly one
    -- Notification of that type. The claim that relies on this constraint is what
    -- stops a differently ID'd repeat of a transition from notifying twice.
    CONSTRAINT uq_notifications_reservation_type UNIQUE (reservation_id, type)
);

-- The read surface: a Customer's Notifications, newest first.
CREATE INDEX ix_notifications_recipient_sent ON notifications (recipient_customer_id, sent_at DESC, id DESC);

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

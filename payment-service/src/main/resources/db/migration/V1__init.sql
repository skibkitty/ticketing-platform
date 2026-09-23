-- Payment schema (one instance, one schema per service — docs/adr/005).
-- Idempotent: compose's postgres-init.sql also creates the schema.
-- The *_at timestamps are DB-owned (DEFAULT now(), insertable=false in JPA):
-- entities never write them, so there is exactly one source of truth for created_at.
CREATE SCHEMA IF NOT EXISTS payment;

SET search_path TO payment;

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL,
    amount_cents INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_payments_reservation_id UNIQUE (reservation_id)
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ NULL
);

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

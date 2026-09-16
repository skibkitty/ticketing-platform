-- Reservation schema (one instance, one schema per service — docs/adr/005).
-- Idempotent: compose's postgres-init.sql also creates the schema.
CREATE SCHEMA IF NOT EXISTS reservation;

SET search_path TO reservation;

CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    venue VARCHAR(255) NOT NULL,
    event_date TIMESTAMPTZ NOT NULL
);

CREATE TABLE seats (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events (id),
    section VARCHAR(50) NOT NULL,
    "row" VARCHAR(50) NOT NULL,
    seat_number INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hold_expires_at TIMESTAMPTZ NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_seats_event_section_row_number UNIQUE (event_id, section, "row", seat_number)
);

CREATE INDEX idx_seats_event_id ON seats (event_id);

CREATE TABLE reservations (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL REFERENCES events (id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reservations_customer_id ON reservations (customer_id);

CREATE TABLE reservation_seats (
    reservation_id BIGINT NOT NULL REFERENCES reservations (id),
    seat_id BIGINT NOT NULL REFERENCES seats (id),
    PRIMARY KEY (reservation_id, seat_id)
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
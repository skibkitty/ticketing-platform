-- Optimistic locking for the CONFIRMED transition (ADR 006 pattern, as on seats):
-- two concurrent PaymentSucceeded events for one reservation must never both confirm it.
ALTER TABLE reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
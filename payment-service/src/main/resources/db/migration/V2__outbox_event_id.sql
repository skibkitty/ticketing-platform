-- Two-phase, for databases that already hold outbox rows: add the column nullable,
-- backfill existing rows in V3, then tighten + index. Mirrors reservation-service V3/V4.
ALTER TABLE outbox_events ADD COLUMN event_id UUID;

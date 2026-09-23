-- Backfill rows created before V2 (existing outbox tables), then make the column
-- mandatory and unique. gen_random_uuid() is core Postgres since 13 — both the
-- compose images and Testcontainers use postgres:16.
UPDATE outbox_events
SET event_id = gen_random_uuid()
WHERE event_id IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN event_id SET NOT NULL;

-- Unique per event, so every re-publish of a row carries the same eventId (ADR 003/004).
CREATE UNIQUE INDEX uq_outbox_events_event_id
    ON outbox_events (event_id);

-- Matches "WHERE published_at IS NULL ORDER BY id ASC LIMIT 20" in OutboxEventRepository.
CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (published_at)
    WHERE published_at IS NULL;

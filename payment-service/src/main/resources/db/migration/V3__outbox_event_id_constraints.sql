UPDATE outbox_events
SET event_id = gen_random_uuid()
WHERE event_id IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN event_id SET NOT NULL;

CREATE UNIQUE INDEX uq_outbox_events_event_id
    ON outbox_events (event_id);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (published_at)
    WHERE published_at IS NULL;

-- When a Payment that settled after its Hold expired was refunded (#52). A Refund is
-- a state of the one Payment per Reservation, not a record of its own, so this is a
-- column beside settled_at: NULL until the Payment reaches REFUNDED, never after.
-- status is VARCHAR(20), so the new 'REFUNDED' value needs no type change.
ALTER TABLE payments ADD COLUMN refunded_at TIMESTAMPTZ NULL;

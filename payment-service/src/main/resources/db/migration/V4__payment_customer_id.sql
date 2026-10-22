-- The Customer a Payment belongs to, so the status read can be scoped by who is
-- asking rather than by which reservation id they name (ADR 016). Taken from the
-- ReservationCreated payload the Payment is born from, which already carries it.
--
-- Nullable, and deliberately left unbackfilled: the owner of a pre-V4 row lives in
-- the reservation schema, which this service does not query (ADR 005). A row with
-- no owner matches no caller, so the read fails closed on it — 404, not a leak.
ALTER TABLE payments ADD COLUMN customer_id BIGINT;

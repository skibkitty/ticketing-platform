-- One Postgres instance, one schema per service (see docs/adr/005-schema-per-service.md).
CREATE SCHEMA IF NOT EXISTS reservation;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS notification;

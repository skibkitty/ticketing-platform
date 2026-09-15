# ADR 005: Schema-per-service in one Postgres instance

**Status:** Accepted

**Context:** True database-per-service isolation uses separate instances so
no service can be affected by another's connection pool or storage.

**Decision:** One Postgres container, one schema per service (`reservation`,
`payment`, `notification`), each with its own Flyway migration history. No
cross-schema queries.

**Consequences:** Keeps the isolation that matters most (no hidden coupling
through shared tables) without three Postgres containers on a laptop.
Explicitly weaker than instance-level isolation — call this out if asked
about production-readiness.

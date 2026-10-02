# Plan: US-1.1 — browse/list catalog endpoints

## Phase 1 — CatalogStore.list (in-memory + Postgres) [checkpoint: c69af89]
- [x] Task: Write failing tests for `CatalogStore.list` in `CatalogStoreSuite` (in-memory): empty store, single page, multi-page, total count (9f4bf68)
- [x] Task: Implement `list` on `CatalogStore.inMemory` to pass those tests (9f4bf68)
- [x] Task: Write failing tests for `CatalogStore.list` in `CatalogStorePostgresSuite` (Testcontainers): same cases against real Postgres (e6eadbe)
- [x] Task: Implement `list` on `CatalogStore.postgres` (`ORDER BY created_at DESC LIMIT/OFFSET` + `SELECT count(*)`) to pass those tests (e6eadbe)
- [x] Task: Conductor - User Manual Verification 'CatalogStore.list' (Protocol in workflow.md) — verified via scripts/verify-catalog-list-store.sh (c69af89), prompting off

## Phase 2 — GET /catalogs endpoint [checkpoint: a5b3f2a]
- [x] Task: Add `InvalidPagination` case to `CatalogError` (279e26a)
- [x] Task: Write failing tests in `CatalogRoutesSuite` for `GET /catalogs`: default pagination, explicit limit/offset, empty-table 200, 400 on invalid limit/offset, `X-Total-Count` header value (279e26a)
- [x] Task: Implement the `GET /catalogs` tapir endpoint in `CatalogRoutes.scala` (query params, validation, `X-Total-Count` header, wiring to `CatalogStore.list`) to pass those tests (279e26a)
- [x] Task: Wire the new endpoint into `Main.scala`'s route list (docs + traced routes) (279e26a)
- [x] Task: Write/extend `CatalogDocsSuite` to cover the new endpoint's presence in generated docs (279e26a)
- [x] Task: Conductor - User Manual Verification 'GET /catalogs endpoint' (Protocol in workflow.md) — verified via scripts/verify-catalog-list-endpoint.sh, prompting off

## Phase 3 — Wrap-up
- [x] Task: Run full suite (`sbt scalafmtCheck test`) and coverage check (67/67 passed, 90.57% stmt / 90.70% branch coverage)
- [x] Task: Update `conductor/tracks.md` backlog (remove US-1.1 line, already captured as a real track) — already satisfied: `/conductor:newTrack` removed it from the backlog when this track was created
- [ ] Task: Conductor - User Manual Verification 'Phase 3 wrap-up' (Protocol in workflow.md)

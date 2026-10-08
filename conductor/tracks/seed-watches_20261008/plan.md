# Plan: Seed luxury-watch catalog data

## Phase 1: Script implementation
- [x] Task: Write `scripts/seed-watches.sh` with the 10 hardcoded watch
  entries (name, description, priceCents, sku), `CATALOG_SERVICE_URL`
  (default `http://localhost:8080`) support, and executable permission.
  (3893b38)
- [x] Task: Implement the dedup check — `GET $CATALOG_SERVICE_URL/catalogs?limit=100`
  parsed via python3, skip any entry whose sku is already present. (3893b38)
- [x] Task: Implement the create path — `POST /catalogs` per non-existing
  entry, track created/skipped/failed counts, continue past individual
  POST failures. (3893b38)
- [x] Task: Print end-of-run summary line and exit non-zero if
  `failed > 0`. (3893b38)
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Manual verification & docs
- [ ] Task: Run the script against a fresh local `docker compose up -d` +
  `sbt run` catalog-service instance; confirm 10 rows created, fields
  correct via `GET /catalogs`.
- [ ] Task: Run the script a second time; confirm 0 new rows, correct
  summary, exit code 0.
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

Note: per workflow.md's TDD step, this track is a shell script seeding a
running service via its public HTTP API — there's no application code to
unit-test (no new Scala files, no new module). "Tests" here are the
scripted manual-verification runs in Phase 2, matching this repo's
existing `verify-*.sh` pattern rather than munit specs.

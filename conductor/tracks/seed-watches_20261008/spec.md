# Spec: Seed luxury-watch catalog data

## Overview
Add `scripts/seed-watches.sh` — a standalone, idempotent shell script that
seeds catalog-service with ~10 luxury-watch catalog items via
`POST /catalogs`, giving gshop's (currently backlog-only) US-1 Catalog
screen realistic demo data once it's wired up.

## Functional Requirements
- New script: `scripts/seed-watches.sh`, following the style of existing
  `scripts/verify-*.sh`.
- Hardcodes 10 luxury-watch entries (name, description, priceCents, sku),
  e.g. Rolex Submariner, Omega Speedmaster, Patek Philippe Nautilus,
  Audemars Piguet Royal Oak, Tag Heuer Carrera, Breitling Navitimer, IWC
  Portugieser, Cartier Santos, Jaeger-LeCoultre Reverso, Vacheron
  Constantin Overseas.
- SKU convention: `watch-<brand>-<model-slug>` (e.g.
  `watch-rolex-submariner`) — invented now since gshop's
  `src/data/watchImages.ts` doesn't exist yet; revisit once that file is
  built.
- Does NOT start/stop the service — assumes catalog-service is already
  running and reachable (OrbStack k8s or local `sbt run`).
- Target URL via `CATALOG_SERVICE_URL` env var, default
  `http://localhost:8080`.
- Idempotent: for each entry, check (via `GET /catalogs`, first 100 rows)
  whether a catalog with that `sku` already exists; skip with a log line
  if so, otherwise `POST /catalogs`.
- A failed POST for one item is logged and counted, but doesn't abort the
  rest of the run.
- Ends with a summary: created / skipped / failed counts; exits non-zero
  if anything failed.

## Non-Functional Requirements
- Plain bash (`set -euo pipefail`) + curl + python3 for JSON parsing —
  matches existing scripts, no new tech-stack.md dependency.

## Acceptance Criteria
- Fresh run against an empty catalogs table creates all 10 rows with
  correct fields.
- A second consecutive run creates 0 new rows (all skipped by sku) and
  exits 0.
- Summary line printed at the end with correct counts.
- `CATALOG_SERVICE_URL` override works.

## Out of Scope
- Matching gshop's real future SKU strings.
- `--reset`/teardown of seeded data.
- Non-HTTP seeding (no direct DB insert, no startup-time seeding).
- Dedup beyond the first 100 existing catalogs.
- Running in CI/deployment automatically.

# Plan: Expand watch catalog from 10 to 100 items

## Phase 1: Append 90 entries [checkpoint: 399c66c]
- [x] Task: Draft 90 real-brand/model watch entries (original descriptions,
  `watch-<brand>-<model-slug>` skus, ~$4,300–$35,000 price band), avoiding
  duplicates with the existing 10 (399c66c)
- [x] Task: Append to `WATCHES` array; verify no sku collisions and
  price-band compliance (399c66c)
- [x] Task: Run the script live against the real catalog-service (driven
  from gshop's `boutique-redesign_20261010` track, Phase 5) — 90 created,
  10 skipped, 0 failed (399c66c)
- [x] Task: Conductor - User Manual Verification 'Expand watch catalog
  from 10 to 100 items' (Protocol in workflow.md) — verified via
  `GET /catalogs` across all pages: 100 items, 100 unique skus

Note: driven entirely from gshop's `boutique-redesign_20261010` track (see
that track's archived spec/plan for full context on the "classy watch
boutique" redesign this catalog growth supports) — this track covers only
the catalog-service-side data change.

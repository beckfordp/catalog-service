# Spec: Expand watch catalog from 10 to 100 items

## Overview
Append 90 new real-brand/model luxury watches to `scripts/seed-watches.sh`'s
existing `WATCHES` array (originally 10 entries from `seed-watches_20261008`),
driven by gshop's `boutique-redesign_20261010` track growing its demo
catalog from 10 to 100 items. Same format, same idempotent script, same
`watch-<brand>-<model-slug>` SKU convention as the original 10.

## Functional Requirements
- 90 new entries appended to the `WATCHES` array: `name|description|priceCents|sku`,
  real brand/model names (not duplicating the original 10), originally-written
  one-line descriptions, prices in the existing ~$4,300–$35,000 band.
- No change to the script's own logic (idempotency check, create loop,
  summary) — pure data addition.

## Non-Functional Requirements
- No new tech-stack dependency.

## Acceptance Criteria
- Running the script against a catalog that already has the original 10
  creates exactly 90 new rows (0 failed), skips the 10 pre-existing ones.
- `GET /catalogs` (paginated) returns 100 items total, all unique skus.

## Out of Scope
- Any change to the script's own idempotency/create/summary logic.
- Real product photography (gshop sources its own placeholder/stock
  imagery separately).

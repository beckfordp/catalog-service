# Project Tracks

This file tracks all major tracks for the project.

- [x] **Track: US-1.2: Redis read-through cache**
  *Link: [./tracks/catalog-list-cache_20261003/](./tracks/catalog-list-cache_20261003/)*

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- Seed a luxury-watch product list (brand/model/description/price/sku) via
  a new `scripts/seed-watches.sh`, POSTing each item to this service's own
  `POST /catalogs` — driven by gshop's US-1 Catalog screen track needing
  real data to render against; SKUs must match the ones gshop's
  `src/data/watchImages.ts` maps to thumbnail placeholders

---

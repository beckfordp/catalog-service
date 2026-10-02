# Project Tracks

This file tracks all major tracks for the project.

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- Generate catalog-service + apply field-spec (infra) — **already done**: this
  repo's generated code already has `name`/`description`/`priceCents`/`sku`
  (create-only) applied from `gluon/specs/catalog.yaml`; prune this line
  rather than re-track it
- US-1.1: browse/list catalog endpoints
- US-1.2: Redis read-through cache

---

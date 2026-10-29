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

- Bulk lookup-by-SKU endpoint (e.g. `GET /catalogs?skus=a,b,c`) — discovered
  2026-10-09: gshop's Cart screen joins cart-service's line items (sku +
  quantity only) against the *entire* catalog list client-side to get
  display name/price, since no endpoint lets it fetch just the SKUs it
  actually needs. This is the first instance of a recurring CQRS-shaped
  gap (a write-side service's minimal data vs. a read-side screen's need
  for a cross-service denormalized view) — US-3 (checkout) and US-8 (order
  history) will likely hit the same shape again. This endpoint is the
  tactical fix for now; if a third call site needs a similar join, revisit
  as an ADR-level decision (BFF service vs. a Kafka-driven CQRS read-model)
  rather than adding another one-off endpoint.

- TD-15.6 — cross-repo consistency hardening: typed CatalogId, ScalaCheck, narrower constraints (see `gluon/TECHNICAL_DEBT.md` TD-15)

---

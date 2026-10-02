# Product Guide — catalog-service

## Context
Part of the **Gluon** platform (v1) — a production-grade microservices
e-commerce platform built pure-FP-first in Scala 3 (Cats Effect / http4s).
See the cross-repo [Gluon Product Vision](../../../docs/product.md) (in the
`gluon/` monorepo root) for the full platform vision, naming scheme, and
goals. This document scopes that vision down to catalog-service's own slice.

## What this service does
catalog-service owns the **Catalog** domain entity — the browsable product
list customers shop from — for Gluon's user stories. It is the only service
permitted to read or write the `catalog` Postgres database
(one-db-per-service). Read-heavy by design: `GET /catalogs` (US-1.1, done
2026-10-02) is the browse/list endpoint; a Redis read-through cache sits in
front of it (US-1.2, not yet built).

Generated via `pure-service-generator` (giter8 template over `purerest`),
field-spec applied from `gluon/specs/catalog.yaml`, then hand-extended per
`gluon/backlogs/catalog-service.md`.

## Domain model
- **Catalog** — `name` (String), `description` (String), `priceCents` (Int),
  `sku` (String, create-only — accepted on `POST /catalogs`, omitted from
  `PATCH`/`PUT` bodies and never changed after creation), plus generator
  defaults `id`, `createdAt`, `updatedAt`. The field-spec
  (`gluon/specs/catalog.yaml`) was represented faithfully by codegen — no
  flagged gaps like order-service's `status` enum-as-String or
  `order_items` collection.
- **Known doc/code drift**: this service's own `README.md` quickstart curl
  examples (`item`/`quantity`/`status` fields) are stale copy-paste from a
  different generated service and don't match the actual generated model
  (`name`/`description`/`priceCents`/`sku`) — worth fixing in the README
  directly, out of scope for this Conductor setup.
- **`GET /catalogs`** (US-1.1, done 2026-10-02) — browse/list endpoint.
  Optional `limit` (default 20, max 100) and `offset` (default 0) query
  params; results ordered newest-first (`created_at DESC`); plain JSON array
  response (no envelope) plus an `X-Total-Count` response header for the
  true total; invalid `limit`/`offset` → `400` (`InvalidPagination`). No
  filtering/sorting beyond newest-first, no cursor-based pagination — both
  explicitly deferred.

## User stories in scope (gluon/docs/user-stories.md)
- US-1.1 — browse/list catalog endpoints (done 2026-10-02)
- US-1.2 — Redis read-through cache

## Sequencing (gluon/PLAN.md)
- **Phase 6 — Catalog** *(independent of the fulfillment spine — can run
  anytime)*: US-1.1, then US-1.2. No external service dependencies — only
  this repo's own Postgres/Redis.
- Exit criteria (per PLAN.md): list/get endpoints tested, cache-hit vs.
  cache-miss path both covered.

## Events
- Publishes: none
- Consumes: none

## Out of scope for this service
- Auth/identity (no user-service yet)
- Cart/checkout (cart-service's job)
- Order/payment data (order-service/payment-service's job) — other services
  never read this service's database directly; any reference to catalog
  data elsewhere (e.g. order-service's `order_items`) is a logical reference
  + snapshot only, never a live lookup or FK

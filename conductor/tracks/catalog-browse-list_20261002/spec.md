# Spec: US-1.1 — browse/list catalog endpoints

## Overview
Add a `GET /catalogs` endpoint to catalog-service so customers can browse the
product catalog, per gluon/docs/user-stories.md US-1. This is the first of
two catalog-service stories in PLAN.md Phase 6; US-1.2 (Redis read-through
cache) is a separate, later track.

## Functional Requirements
- `GET /catalogs` — new tapir endpoint alongside the existing
  create/get/update/replace/delete endpoints in `CatalogRoutes.scala`.
- Query params: `limit` (default 20, max 100) and `offset` (default 0),
  both optional integers.
- Results ordered newest-first (`created_at DESC`) — no filtering/sorting
  options in this first cut.
- Response body: a plain JSON array of `CatalogResponse` (same shape as the
  existing single-resource response) — no wrapper object, consistent with
  product-guidelines.md's "no response envelope" convention.
- Total count returned via an `X-Total-Count` response header, so a client
  can tell if more pages remain.
- `CatalogStore` gets a new `list(limit: Int, offset: Int): F[(List[Catalog], Long)]`
  method (catalog page + total count), implemented for both `inMemory` and
  `postgres` backends — Postgres uses `ORDER BY created_at DESC LIMIT/OFFSET`
  plus a `SELECT count(*)`.

## Non-Functional Requirements
- Follows existing structured-logging pattern (method/path logged on
  request, catalog_id-style context where applicable).
- Swagger/OpenAPI docs stay in sync by construction (tapir-generated,
  `CatalogDocsSuite` extended to cover the new endpoint).

## Acceptance Criteria
- `GET /catalogs` with no query params returns up to 20 catalogs,
  newest-first, with `X-Total-Count` set to the true total.
- `GET /catalogs?limit=5&offset=5` returns the correct page.
- `limit<=0`, `limit>100`, or `offset<0` → `400 Bad Request` with an
  `ErrorResponse` body (new `CatalogError` case, e.g. `InvalidPagination`).
- Empty catalog table → `200 OK` with `[]` and `X-Total-Count: 0` (not 404).
- Both `CatalogStoreSuite` (in-memory) and `CatalogStorePostgresSuite`
  (Testcontainers) cover `list`: empty, single page, multi-page, and the
  total-count value.
- `CatalogRoutesSuite` covers the new endpoint: defaults, explicit
  pagination, and the 400 validation cases.

## Out of Scope
- Redis read-through cache (US-1.2 — separate track).
- Any filtering (by name, price range, etc.) or sorting other than
  newest-first.
- Cursor-based pagination.

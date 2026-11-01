# Spec: REST docs convention (gluon TD-20.7)

## Overview
catalog-service `/docs` shows bare paths and unexplained schemas. Apply the
TD-20 convention (purerest README "Documenting endpoints"), mirroring
order-service `rest-docs_20261101`, and assert it in a test.

## Functional Requirements
- FR1: purerestlib 0.4.0 → 0.7.3; suite green; tech-stack.md synced.
- FR2: Per-endpoint error outputs — already narrowed by TD-16.4
  (`catalog-review-fixes_20261101`): POST 400 (invalid catalog); GET list
  400 (pagination); GET by id 404; PATCH/PUT 400 + 404; DELETE 404. Pin with
  a `CatalogDocsSuite` test walking each endpoint's `errorOutput` statuses.
- FR3: Every endpoint `.summary`/`.description`/`.tag` (`catalogs`,
  `health`) — see Appendix.
- FR4: `id` path, `limit`/`offset` query params and the `X-Total-Count`
  header described + example.
- FR5: Every request/response DTO + field `@description`, scalar fields
  `@encodedExample`, body `.example` on every request/response.
- FR6: Every error variant described + example body; fixed messages share
  one value between example and mapping.
- FR7: `CatalogDocsSuite`: `Docs.undocumented(all 8 served endpoints) ==
  Nil` (the list Main passes to `Docs.routes`).
- FR8: `scripts/verify-rest-docs.sh` checks served `/docs/docs.yaml`.

## Non-Functional
- Wire format unchanged (encode/decode identical); scalafmtCheck clean.
- Examples use UUID-shaped ids (CatalogId is a server-minted UUID) and the
  real seed SKU convention `watch-<brand>-<model-slug>`; no secrets.

## Acceptance Criteria
- Full suite green, zero gaps, verify script PASS, CI green.

## Out of Scope
- Behaviour changes; bulk lookup-by-SKU backlog item.

## Appendix: descriptions (self-approved 2026-11-01, prompting off)

### Endpoints
| Endpoint | summary | description |
|---|---|---|
| POST /catalogs | Create a catalog entry | Adds a product. Blank `name`/`sku` or `priceCents` < 0 → 400. Not idempotent (new id each call; SKU uniqueness not enforced). Cached list pages not invalidated. |
| GET /catalogs | List catalog entries | One page, newest first (ties by id); `X-Total-Count` = total. Redis-cached per (limit, offset), 60s default TTL, no write invalidation; falls back to Postgres if Redis fails. Bad limit/offset → 400. |
| GET /catalogs/{id} | Get a catalog entry | From Postgres (not cached). 404 unknown, 400 malformed id. |
| PATCH /catalogs/{id} | Update a catalog entry | Sets name/description/priceCents (all required; sku immutable). Idempotent. Body checked before lookup: 400, then 404. |
| PUT /catalogs/{id} | Update a catalog entry (replace) | Same as PATCH. |
| DELETE /catalogs/{id} | Delete a catalog entry | Hard delete; repeat → 404. |
| GET /health | Liveness check | 200 while up. |
| GET /health/ready | Readiness check | 200 when Postgres answers a ping, else 503 (Redis not checked). |

### Error variants
| Status | When | Example body |
|---|---|---|
| 404 | No entry with that id | `{"error":"Catalog not found"}` |
| 400 | limit outside 1–100 / offset < 0 | `{"error":"Invalid pagination parameters"}` |
| 400 | blank name/sku, priceCents < 0 | `{"error":"priceCents must be at least 0, got -1"}` |
| 503 | ready: Postgres didn't answer | (no body) |

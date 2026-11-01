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

## Appendix: approved descriptions (2026-11-01)

> Approved by the user 2026-11-01, exactly as proposed. Applied to code in
> Phase 3.

### Endpoints
| Endpoint | tag | summary | description |
|---|---|---|---|
| POST /catalogs | `catalogs` | Create a catalog entry | Adds a product. Blank `name`/`sku` or `priceCents` < 0 → 400. Not idempotent (new id each call; SKU uniqueness not enforced). Cached list pages are not invalidated. |
| GET /catalogs | `catalogs` | List catalog entries | One page, newest first (ties by id); `X-Total-Count` is the true total. Redis-cached per (limit, offset) with a 60s default TTL and no write invalidation; falls back to Postgres if Redis fails. Bad limit/offset → 400. |
| GET /catalogs/{id} | `catalogs` | Get a catalog entry | Read from Postgres (not cached). 404 unknown id; 400 malformed id (tapir decode failure). |
| PATCH /catalogs/{id} | `catalogs` | Update a catalog entry | Sets name/description/priceCents (all required; `sku` immutable). Safe to repeat (only `updatedAt` advances). Body validated before lookup: 400, then 404. |
| PUT /catalogs/{id} | `catalogs` | Update a catalog entry (replace) | Same as PATCH. |
| DELETE /catalogs/{id} | `catalogs` | Delete a catalog entry | Hard delete; repeating returns 404. |
| GET /health | `health` | Liveness check | 200 while the process is up. Checks no dependencies. |
| GET /health/ready | `health` | Readiness check | 200 when Postgres answers a ping, else 503 (Redis is not checked). |

### Parameters and headers
| Where | Name | Description | Example |
|---|---|---|---|
| path (GET/PATCH/PUT/DELETE /catalogs/{id}) | `id` | Catalog id (UUID). A malformed id returns 400. | `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90` |
| query (GET /catalogs) | `limit` | Maximum entries to return (1–100, default 20). | `20` |
| query (GET /catalogs) | `offset` | Entries to skip (default 0). | `0` |
| response header (GET /catalogs, 200) | `X-Total-Count` | Total catalog entries, independent of limit/offset. | `"42"` |

### DTOs and body examples
Every case class gets an `@description`, every field an `@description`, every
scalar field an `@encodedExample`, and every request/response body a `.example`
(including the `List[CatalogResponse]` list body and each error body). No body
examples for `DELETE /catalogs/{id}` (204) or the health endpoints — no bodies.

**CreateCatalogRequest** — "A new catalog entry."
| Field | Description | Example |
|---|---|---|
| `name` | Product name. | `Rolex Submariner` |
| `description` | What the product is. | `Iconic diver's watch, 41mm stainless steel case` |
| `priceCents` | Price in cents, at least 0. | `950000` |
| `sku` | Stock-keeping unit. | `watch-rolex-submariner` |

```json
{"name":"Rolex Submariner","description":"Iconic diver's watch, 41mm stainless steel case","priceCents":950000,"sku":"watch-rolex-submariner"}
```

**UpdateCatalogRequest** — "Fields to update; `sku` is immutable."
| Field | Description | Example |
|---|---|---|
| `name` | Product name. | `Rolex Submariner` |
| `description` | What the product is. | `Iconic diver's watch, 41mm stainless steel case` |
| `priceCents` | Price in cents, at least 0. | `950000` |

```json
{"name":"Rolex Submariner","description":"Iconic diver's watch, 41mm stainless steel case","priceCents":950000}
```

**CatalogResponse** — "A catalog entry as stored."
| Field | Description | Example |
|---|---|---|
| `id` | Catalog id (UUID). | `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90` |
| `name` | Product name. | `Rolex Submariner` |
| `description` | What the product is. | `Iconic diver's watch, 41mm stainless steel case` |
| `priceCents` | Price in cents. | `950000` |
| `sku` | Stock-keeping unit. | `watch-rolex-submariner` |
| `createdAt` | When the entry was created (UTC). | `2026-11-01T09:30:00Z` |
| `updatedAt` | When the entry last changed (UTC). | `2026-11-01T09:45:00Z` |

Body example (single response, and the element of the `GET /catalogs` list example):

```json
{"id":"3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90","name":"Rolex Submariner","description":"Iconic diver's watch, 41mm stainless steel case","priceCents":950000,"sku":"watch-rolex-submariner","createdAt":"2026-11-01T09:30:00Z","updatedAt":"2026-11-01T09:45:00Z"}
```

`createdAt`/`updatedAt` are `java.time.Instant`; `@encodedExample` takes the
encoded ISO-8601 string, matching order-service's documented pattern.

**ErrorResponse** — "Error body."
| Field | Description | Example |
|---|---|---|
| `error` | Human-readable reason. | `Catalog not found` |

Each error variant's body example is in the table below.

### Error variants
| Status | Endpoint(s) | When | Example body |
|---|---|---|---|
| 400 | POST/PATCH/PUT /catalogs… | `name` blank | `{"error":"name must not be blank"}` |
| 400 | POST/PATCH/PUT /catalogs… | `priceCents` < 0 | `{"error":"priceCents must be at least 0, got -1"}` |
| 400 | POST /catalogs | `sku` blank | `{"error":"sku must not be blank"}` |
| 400 | GET /catalogs | limit outside 1–100 or offset < 0 | `{"error":"Invalid pagination parameters"}` |
| 404 | GET/PATCH/PUT/DELETE /catalogs/{id} | No entry with that id | `{"error":"Catalog not found"}` |
| 503 | GET /health/ready | Postgres didn't answer the ping | (no body — status-code output described) |

Each 400/404 `oneOf` variant's `ErrorResponse` body carries its description and
example; each fixed message is defined once and shared by the example and the
response mapping (`CatalogRules` for the three invalid-catalog messages,
`notFoundVariant` for 404, `invalidPaginationOutput` for pagination). The 503 has
no body, so its `statusCode` output carries the description.

Malformed path ids and malformed JSON request bodies fail tapir decoding (400,
plain-text body) before endpoint logic runs. They are not declared `oneOf`
variants, so the endpoint descriptions above cover them and they do not appear
as documented error outputs.

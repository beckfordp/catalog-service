# Spec: catalog-service review fixes (gluon TD-16.4)

## Overview
Full review of catalog-service main + test code (2026-11-01) against
`code_styleguides/scala-fp.md` (Review checklist) and `general.md`. Baseline:
78 tests green at `27fcaa0`. Wire contracts (REST payload shapes) unchanged.

## Findings

### High (checklist "no")
- **H1 Business rule in the shell.** `GET /catalogs` pagination rule (default
  20, 1 ≤ limit ≤ 100, offset ≥ 0) decided inline in
  `listCatalogsServerEndpoint`. → pure `domain.Page.from(limit, offset):
  Either[InvalidPagination.type, Page]`, ScalaCheck property.
- **H2 Over-broad capabilities.** `listCatalogsServerEndpoint` takes `Async`
  (needs `MonadThrow`); `CatalogListCache.fromRedisCommands` `Async` (needs
  `Functor`); `CatalogStore.inMemory` / `CatalogListCache.inMemory` `Sync`
  (need `Concurrent` + `Clock` + `UUIDGen` / `Concurrent`).
- **H3 Request fields never parsed.** POST/PATCH/PUT accept negative
  `priceCents` and blank `name`/`sku` straight into the store. → pure
  `domain.CatalogRules` returning `Either[InvalidCatalog, _]`; typed 400
  (`ErrorResponse` body, same shape as every other error).

### Medium
- **M1 Imprecise endpoint error types.** Every endpoint is typed
  `CatalogError` but its output encodes one case only (encode ignores the
  value), so a wrong case would be silently mislabelled (e.g.
  `InvalidPagination` → 404 "Catalog not found"). → per-endpoint error types
  (`CatalogNotFound.type`, `InvalidPagination.type`, `InvalidCatalog`,
  unions for PATCH/PUT), variants defined once.
- **M2 Cache failure fails the read.** A Redis read or write error turns
  `GET /catalogs` into a 500 although Postgres is fine. → fall back to the
  store on read error, log-and-continue on write error (order-service
  `listOrdersServerEndpoint` pattern).
- **M3 Unstable pagination.** `ORDER BY created_at DESC` (Postgres and
  in-memory) has no tiebreaker; rows with equal `created_at` can repeat or
  vanish across pages. → `created_at DESC, id DESC` in both.
- **M4 SKU not unique.** No unique constraint; duplicate SKUs make
  cart/order SKU joins ambiguous. **Deferred**: needs a migration that fails
  on existing duplicate rows and a new 409 response (contract change) —
  parent decision.

### Low
- **L1** `Catalog` entity and `CatalogError` live outside `domain/`. → move
  (pure, no `F`).
- **L2** Undecodable cache entry silently treated as a miss. **Skipped**: a
  miss is the right degradation and the next `set` overwrites it.
- **L3** PATCH requires the full body (PUT semantics). **Skipped**: wire
  contract.
- **L4** `redis.list-ttl-seconds` not validated (0 makes `SETEX` fail).
  **Skipped**: with M2 a failing write is logged, not surfaced; config only.

## Functional Requirements
- FR-1 H1, FR-2 H2, FR-3 H3, FR-4 M1, FR-5 M2, FR-6 M3, FR-7 L1 as above.

## Non-Functional
- TDD (red first) per workflow; >80% coverage; scalafmtCheck clean.
- REST payload shapes unchanged; only new behaviour is H3's 400.

## Acceptance Criteria
- Each fixed finding has a failing-first test now green; full suite green;
  CI green.

## Out of Scope
- M4 (SKU uniqueness), REST docs (TD-20.7), bulk lookup-by-SKU backlog item.

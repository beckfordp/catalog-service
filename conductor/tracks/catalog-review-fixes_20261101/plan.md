# Plan — catalog-service review fixes (gluon TD-16.4)

## Phase 1: Pure core (H1, H3, L1) [checkpoint: 14fb435]
- [x] Task: tests first — `PageSuite` (table + ScalaCheck), `CatalogRulesSuite`, route 400s for negative price / blank name / blank sku on POST/PATCH/PUT (red) → `domain.Page`, `domain.CatalogRules`, `InvalidCatalog`; move `Catalog`/`CatalogError` into `domain/`; green — red: compile + route 201s; fields validated before lookup; store signature kept (route passes parsed fields); 93/93 ff6ca38
- [x] Task: Conductor - User Manual Verification 'Phase 1: Pure core' (Protocol in workflow.md) — covered by suite

## Phase 2: Edge and effects (H2, M1, M2) [checkpoint: 68c0306]
- [x] Task: tests first — cache read error and write error still serve `GET /catalogs` from the store, logged WARN (red) → fallback; per-endpoint error types; narrow capabilities; green — red 2/2 (500); store inMemory Concurrent+Clock+UUIDGen, cache inMemory Concurrent, fromRedisCommands Functor, list MonadThrow; 95/95 48137f4
- [x] Task: Conductor - User Manual Verification 'Phase 2: Edge and effects' (Protocol in workflow.md) — covered by suite

## Phase 3: Stable pagination (M3)
- [ ] Task: tests first — tied `created_at` pages are disjoint and ordered by id desc (in-memory under `TestControl`, Postgres via direct insert) (red) → `ORDER BY created_at DESC, id DESC`; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Stable pagination' (Protocol in workflow.md)

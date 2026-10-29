# TD-15.6 Implementation Plan

## Phase 1 — Typed CatalogId
- [x] Failing tests: CatalogId suite + malformed route id → 400; implement domain type, store, routes; update fixtures. — 4762e42
- [x] Task: Conductor - User Manual Verification 'Phase 1 — Typed ids' (Protocol in workflow.md) — covered by tests

## Phase 2 — ScalaCheck and narrowing
- [x] Add `munit-scalacheck` + properties; narrow `Cors`/`HealthRoutes`/route handlers. — same commit
- [x] Task: Conductor - User Manual Verification 'Phase 2 — Purity' (Protocol in workflow.md) — covered by tests

## Phase 3 — Verification
- [x] Full suite + coverage + scalafmt. — 78 tests, 91.4%/90.7% coverage
- [x] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md) — covered by the suite

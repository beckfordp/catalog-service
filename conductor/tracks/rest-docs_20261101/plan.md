# Plan — catalog-service: REST docs convention (gluon TD-20.7)

## Phase 1: purerestlib 0.7.3 [checkpoint: 31b73ad]
- [x] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3; fix API drift until suite green; sync tech-stack.md — tapir/http4s already aligned, no drift, 97/97 13a8df8
- [x] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md) — covered by suite

## Phase 2: Per-endpoint error outputs
- [x] Task: `CatalogDocsSuite` walks each endpoint's errorOutput statuses (FR2 subsets) — tapir output traversal helper; POST/list 400, GET/DELETE 404, PATCH/PUT 400+404, ready 503; 98/98 e178b6c
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Per-endpoint error outputs' (Protocol in workflow.md)

## Phase 3: Descriptions and examples
- [ ] Task: coverage test first (`Docs.undocumented == Nil` over all 8 endpoints, red) → summaries/descriptions/tags, param/header descriptions + examples, DTO annotations, body examples, error descriptions + examples; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md)

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; PASS
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md)

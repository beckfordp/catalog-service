# Plan — catalog-service: REST docs convention (gluon TD-20.7)

## Phase 1: purerestlib 0.7.3
- [ ] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3; fix API drift until suite green; sync tech-stack.md
- [ ] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md)

## Phase 2: Per-endpoint error outputs
- [ ] Task: `CatalogDocsSuite` walks each endpoint's errorOutput statuses (FR2 subsets)
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Per-endpoint error outputs' (Protocol in workflow.md)

## Phase 3: Descriptions and examples
- [ ] Task: coverage test first (`Docs.undocumented == Nil` over all 8 endpoints, red) → summaries/descriptions/tags, param/header descriptions + examples, DTO annotations, body examples, error descriptions + examples; green
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md)

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; PASS
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md)

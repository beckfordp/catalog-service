# Plan: Expose X-Total-Count via CORS

## Phase 1: Expose-headers fix [checkpoint: a1380ff]

- [x] Task: Write failing `CorsSuite` test — cross-origin `GET` response's `Access-Control-Expose-Headers` contains `X-Total-Count` (Red) `a1380ff`
- [x] Task: Add `.withExposeHeadersIn(Set(ci"X-Total-Count"))` to `Cors.middleware` (Green) `a1380ff`
- [x] Task: Run full `sbt test` (77/77 green); extend `scripts/verify-cors.sh` with a live expose-headers check `a1380ff`
- [x] Task: Conductor - User Manual Verification 'Expose-headers fix' (Protocol in workflow.md) — `scripts/verify-cors.sh` run live (fresh Postgres + real `sbt run`), all checks including the new one passed. Then redeployed for real via `bin/k8s-local-up catalog-service` (missing `GITHUB_TOKEN`/`GITHUB_ACTOR` turned out to be only a `WARN` in that script, not a hard requirement — the purerestlib dependency was already locally cached, so the build/deploy succeeded with no token needed). Verified against the real pod: `curl` with an `Origin` header shows `Access-Control-Expose-Headers: X-Total-Count`; a real browser `fetch()` reads `X-Total-Count` as `"100"` (previously `null`); gshop's live Catalog screen's "Load more" button reappeared and correctly paged through all 100 real items

Single phase: a small, well-scoped additive fix to an already-shipped
middleware, no natural phase boundary.

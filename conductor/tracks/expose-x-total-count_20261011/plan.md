# Plan: Expose X-Total-Count via CORS

## Phase 1: Expose-headers fix [checkpoint: a1380ff]

- [x] Task: Write failing `CorsSuite` test — cross-origin `GET` response's `Access-Control-Expose-Headers` contains `X-Total-Count` (Red) `a1380ff`
- [x] Task: Add `.withExposeHeadersIn(Set(ci"X-Total-Count"))` to `Cors.middleware` (Green) `a1380ff`
- [x] Task: Run full `sbt test` (77/77 green); extend `scripts/verify-cors.sh` with a live expose-headers check `a1380ff`
- [x] Task: Conductor - User Manual Verification 'Expose-headers fix' (Protocol in workflow.md) — `scripts/verify-cors.sh` run live (fresh Postgres + real `sbt run`), all checks including the new one passed. Verification against the actual k8s-deployed pod (and gshop's live browser Catalog screen) pending a redeploy — `bin/k8s-local-up catalog-service` needs `GITHUB_TOKEN`/`GITHUB_ACTOR`, not set in this shell, same blocker hit on `inventory-service`'s `admin-screen_20261010` redeploy

Single phase: a small, well-scoped additive fix to an already-shipped
middleware, no natural phase boundary.

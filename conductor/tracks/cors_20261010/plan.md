# Plan: Backport CORS support from pure-service-generator (TD-1.2)

## Phase 1: CORS middleware

- [x] Task: Write failing `CorsSuite` (Red) — 3 tests against a dummy `HttpRoutes.of[IO]` wrapped in `Cors.middleware`: (1) GET with `Origin` header → response carries `Access-Control-Allow-Origin`; (2) `OPTIONS` preflight → correct `Access-Control-Allow-Methods`/`Access-Control-Allow-Headers`; (3) no `Access-Control-Allow-Credentials` header present. Confirm compile failure (no `Cors` object yet).
- [x] Task: Add `Cors.scala` (package `catalogservice`) — verbatim port of the generator's `Cors.middleware[F[_]: Async]`: `CORS.policy.withAllowOriginAll.withAllowMethodsIn(Set(GET,POST,PATCH,PUT,DELETE)).withAllowHeadersIn(Set(ci"Content-Type")).withAllowCredentials(false).apply(routes)`. Run `CorsSuite` — confirm green (Green phase). `2ff5b15`
- [x] Task: Wire `Cors.middleware` into `Main.scala`, wrapping `routes` (the already-composed traced+metered routes) before `.withHttpApp(routes.orNotFound)`. Rerun full `sbt test` — confirm no regressions (76/76 passed). `2ff5b15`
- [x] Task: Verify coverage (`sbt coverage test coverageReport`) — confirmed `Cors.scala` at 100% statement/branch coverage.
- [x] Task: Add `scripts/verify-cors.sh` (matching this repo's existing `verify-*.sh` convention) — starts Postgres via `docker compose up -d`, runs `sbt run`, `curl` with `Origin` header against `GET /catalogs` and an `OPTIONS` preflight, asserts the expected headers, cleans up via `trap`. Ran it for real — passed end-to-end. `a1a1a64`
- [x] Task: Conductor - User Manual Verification 'CORS middleware' (Protocol in workflow.md) — `/prompt off` active: ran `scripts/verify-cors.sh` directly instead of an interactive walkthrough — all checks passed (GET /catalogs: 200 + ACAO header, no Allow-Credentials; OPTIONS preflight: 200 + Allow-Methods includes POST + Allow-Headers includes Content-Type).

## Notes on `/prompt off`
Per-task Plan Mode review and commit-approval prompts are skipped for this track (marker active since 2026-10-10) — each task is implemented per this already-approved plan and committed directly, announced in one line.

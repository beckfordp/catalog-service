# Plan: Backport CORS support from pure-service-generator (TD-1.2)

## Phase 1: CORS middleware

- [ ] Task: Write failing `CorsSuite` (Red) — 3 tests against a dummy `HttpRoutes.of[IO]` wrapped in `Cors.middleware`: (1) GET with `Origin` header → response carries `Access-Control-Allow-Origin`; (2) `OPTIONS` preflight → correct `Access-Control-Allow-Methods`/`Access-Control-Allow-Headers`; (3) no `Access-Control-Allow-Credentials` header present. Confirm compile failure (no `Cors` object yet).
- [ ] Task: Add `Cors.scala` (package `catalogservice`) — verbatim port of the generator's `Cors.middleware[F[_]: Async]`: `CORS.policy.withAllowOriginAll.withAllowMethodsIn(Set(GET,POST,PATCH,PUT,DELETE)).withAllowHeadersIn(Set(ci"Content-Type")).withAllowCredentials(false).apply(routes)`. Run `CorsSuite` — confirm green (Green phase).
- [ ] Task: Wire `Cors.middleware` into `Main.scala`, wrapping `routes` (the already-composed traced+metered routes) before `.withHttpApp(routes.orNotFound)`. Rerun full `sbt test` — confirm no regressions.
- [ ] Task: Verify coverage (`sbt coverage test coverageReport`) — confirm `Cors.scala` at 100% statement/branch coverage.
- [ ] Task: Add `scripts/verify-cors.sh` (matching this repo's existing `verify-*.sh` convention) — starts Postgres via `docker compose up -d`, runs `sbt run`, `curl` with `Origin` header against `GET /catalogs` and an `OPTIONS` preflight, asserts the expected headers, cleans up via `trap`. Run it for real — confirm it passes end-to-end.
- [ ] Task: Conductor - User Manual Verification 'CORS middleware' (Protocol in workflow.md) — since `/prompt off` is active for this repo, this step runs as: execute `scripts/verify-cors.sh` directly and report its result, rather than an interactive walkthrough.

## Notes on `/prompt off`
Per-task Plan Mode review and commit-approval prompts are skipped for this track (marker active since 2026-10-10) — each task is implemented per this already-approved plan and committed directly, announced in one line.

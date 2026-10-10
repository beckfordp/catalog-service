# Spec: Backport CORS support from pure-service-generator (TD-1.2)

## Overview
catalog-service has no CORS support (confirmed 2026-10-08 via gshop's browser requests). `pure-service-generator`'s `cors_20261010` track (archived) already designed, implemented, tested, and verified the fix at the template level. This track ports that exact, already-proven pattern into catalog-service as-generated — no new design decisions, a direct port.

## Functional Requirements
1. Add `Cors.scala` (package `catalogservice`) — identical to the generator's `Cors.middleware[F[_]: Async](routes: HttpRoutes[F]): HttpRoutes[F]`: `CORS.policy.withAllowOriginAll.withAllowMethodsIn(Set(GET,POST,PATCH,PUT,DELETE)).withAllowHeadersIn(Set(ci"Content-Type")).withAllowCredentials(false).apply(routes)`.
2. Wire `Cors.middleware` into `Main.scala`, wrapping the fully-composed routes (tracing + metrics + docs) before `.withHttpApp(routes.orNotFound)` — same insertion point as the generator fix.
3. Add `CorsSuite.scala` — same 3 assertions as the generator's version (GET w/ Origin → ACAO header; OPTIONS preflight → correct Allow-Methods/Allow-Headers; no Allow-Credentials), against a dummy route.

## Non-Functional Requirements
- No new dependency (`org.http4s.server.middleware.CORS` already ships via `http4s-server`).
- No change to catalog-service's actual domain routes/logic — purely an additive middleware wrap.

## Acceptance Criteria
- [ ] `CorsSuite` passes (3 tests), 100% coverage on `Cors.scala`.
- [ ] Full `sbt test` suite green, no regressions.
- [ ] Real verification: start catalog-service for real, `curl` with an `Origin` header against `/catalogs` shows `Access-Control-Allow-Origin`; an `OPTIONS` preflight shows the right `Access-Control-Allow-Methods`/`-Headers`.

## Out of Scope
- Any change to the CORS *policy* itself (allow-all origins, no credentials) — already decided at the generator level, not re-litigated per service.
- The other 5 services — each is tracked (and will be ported) as its own separate track.

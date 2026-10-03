# Plan: US-1.2 — Redis read-through cache

## Phase 1 — Tech stack + local infra [checkpoint: 9f6018e]
- [x] Task: Add `redis4cats-effects`/`redis4cats-log4cats` (runtime) and `testcontainers-scala-redis` (test) to `build.sbt`, matching order-service's pinned versions (2bd350d)
- [x] Task: Add a `redis:7-alpine` service to `docker-compose.yml` (local-dev-only, no persistence), matching order-service's (2bd350d)
- [x] Task: Add `RedisConfig(uri, listTtlSeconds)` to `CatalogServiceConfig`, update `application.conf`/test resources accordingly (2bd350d)
- [x] Task: Conductor - User Manual Verification 'Tech stack + local infra' (Protocol in workflow.md) — verified via scripts/verify-redis-config.sh, prompting off

## Phase 2 — CatalogListCache [checkpoint: bf28577]
- [x] Task: Write failing tests in a new `CatalogListCacheSuite` (Testcontainers Redis, mirroring `OrderHistoryCacheSuite`): miss on empty, hit after set with matching limit/offset, miss with different limit/offset, TTL expiry (08fc6a2)
- [x] Task: Implement `CatalogListCache` (trait + `fromRedisCommands` + `resource`) to pass those tests — cache key `catalog-list:<limit>:<offset>`, JSON-encoded `(List[CatalogResponse], Long)` value (08fc6a2)
- [x] Task: Conductor - User Manual Verification 'CatalogListCache' (Protocol in workflow.md) — verified via scripts/verify-catalog-list-cache.sh, prompting off

## Phase 3 — Wire into GET /catalogs [checkpoint: 64fdd0b]
- [x] Task: Write failing tests in `CatalogRoutesSuite` for cache-aside behavior: first call misses and hits the store, second identical call is a cache hit (store not called again, e.g. via a store fake that errors on a 2nd call), response/`X-Total-Count` identical on both; existing 400 validation tests still pass (9cb785d)
- [x] Task: Make `listCatalogsServerEndpoint` take a `CatalogListCache[F]` parameter and implement cache-aside logic (hit/miss, structured `cache` log context) to pass those tests (9cb785d)
- [x] Task: Wire `CatalogListCache.resource` into `Main.scala` and pass it to `listCatalogsServerEndpoint` (9cb785d)
- [x] Task: Conductor - User Manual Verification 'GET /catalogs cache-aside' (Protocol in workflow.md) — verified via scripts/verify-catalog-list-cache-endpoint.sh, prompting off

## Phase 4 — Wrap-up
- [x] Task: Run full suite (`sbt scalafmtCheck test`) and coverage check (73/73 passed, 91.62% stmt / 91.49% branch coverage)
- [x] Task: Conductor - User Manual Verification 'Phase 4 wrap-up' (Protocol in workflow.md) — re-ran all three track verify scripts (verify-redis-config.sh, verify-catalog-list-cache.sh, verify-catalog-list-cache-endpoint.sh), all pass; prompting off

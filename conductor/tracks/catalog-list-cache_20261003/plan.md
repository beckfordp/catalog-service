# Plan: US-1.2 — Redis read-through cache

## Phase 1 — Tech stack + local infra
- [ ] Task: Add `redis4cats-effects`/`redis4cats-log4cats` (runtime) and `testcontainers-scala-redis` (test) to `build.sbt`, matching order-service's pinned versions
- [ ] Task: Add a `redis:7-alpine` service to `docker-compose.yml` (local-dev-only, no persistence), matching order-service's
- [ ] Task: Add `RedisConfig(uri, listTtlSeconds)` to `CatalogServiceConfig`, update `application.conf`/test resources accordingly
- [ ] Task: Conductor - User Manual Verification 'Tech stack + local infra' (Protocol in workflow.md)

## Phase 2 — CatalogListCache
- [ ] Task: Write failing tests in a new `CatalogListCacheSuite` (Testcontainers Redis, mirroring `OrderHistoryCacheSuite`): miss on empty, hit after set with matching limit/offset, miss with different limit/offset, TTL expiry
- [ ] Task: Implement `CatalogListCache` (trait + `fromRedisCommands` + `resource`) to pass those tests — cache key `catalog-list:<limit>:<offset>`, JSON-encoded `(List[CatalogResponse], Long)` value
- [ ] Task: Conductor - User Manual Verification 'CatalogListCache' (Protocol in workflow.md)

## Phase 3 — Wire into GET /catalogs
- [ ] Task: Write failing tests in `CatalogRoutesSuite` for cache-aside behavior: first call misses and hits the store, second identical call is a cache hit (store not called again, e.g. via a store fake that errors on a 2nd call), response/`X-Total-Count` identical on both; existing 400 validation tests still pass
- [ ] Task: Make `listCatalogsServerEndpoint` take a `CatalogListCache[F]` parameter and implement cache-aside logic (hit/miss, structured `cache` log context) to pass those tests
- [ ] Task: Wire `CatalogListCache.resource` into `Main.scala` and pass it to `listCatalogsServerEndpoint`
- [ ] Task: Conductor - User Manual Verification 'GET /catalogs cache-aside' (Protocol in workflow.md)

## Phase 4 — Wrap-up
- [ ] Task: Run full suite (`sbt scalafmtCheck test`) and coverage check
- [ ] Task: Conductor - User Manual Verification 'Phase 4 wrap-up' (Protocol in workflow.md)

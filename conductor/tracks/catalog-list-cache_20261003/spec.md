# Spec: US-1.2 — Redis read-through cache

## Overview
Add a cache-aside Redis cache in front of `GET /catalogs` (US-1.1), per
gluon/docs/user-stories.md US-1 and system-design.md's Redis column for
catalog-service ("read-through cache"). Follows the Gluon pattern order-service
established for its own read cache (`OrderHistoryCache`, US-8.1): redis4cats,
cache-aside, TTL-only freshness, Testcontainers-backed tests against a real
Redis.

## Functional Requirements
- New `CatalogListCache[F[_]]` trait: `get(limit, offset): F[Option[(List[CatalogResponse], Long)]]`
  and `set(limit, offset, catalogs, total): F[Unit]`.
- Cache key encodes the exact pagination params, e.g. `catalog-list:<limit>:<offset>`
  — a request for `limit=20&offset=0` and one for `limit=5&offset=10` are
  cached independently; invalid-pagination requests (which already 400 before
  reaching the store) never touch the cache.
- Cached value is both the page and its total count (JSON-encoded), so a
  cache hit still returns a correct `X-Total-Count` header without querying
  the store.
- `listCatalogsServerEndpoint` becomes cache-aside: hit → return cached
  value as-is; miss → call `CatalogStore.list`, cache the result with a
  configurable TTL, return it. Structured log line distinguishes `cache=hit`
  vs `cache=miss`, matching `OrderHistoryCache`'s logging shape.
- TTL-only freshness: creating/updating/deleting a catalog does **not**
  invalidate any cached page — a write can take up to the TTL to show up in
  a previously-cached list. Matches order-service's established pattern
  (explicitly out of scope there too).
- New Redis config: `RedisConfig(uri: String, listTtlSeconds: Int)` in
  `CatalogServiceConfig`, default TTL 60 seconds, loaded the same way as
  order-service's `historyTtlSeconds`.
- `docker-compose.yml` gets a `redis:7-alpine` service (local-dev-only, no
  persistence), matching order-service's.
- `build.sbt` gets `redis4cats-effects`/`redis4cats-log4cats` (runtime) and
  `testcontainers-scala-redis` (test), matching order-service's versions.

## Non-Functional Requirements
- Cache-miss path's existing structured logging (method/path) is unchanged;
  only the cache hit/miss distinction and its context (`limit`, `offset`,
  `cache`) are added.
- No change to `CatalogStore` or the Postgres schema — this track is purely
  an additive caching layer in front of the existing `GET /catalogs` route.

## Acceptance Criteria
- A cache miss followed by a `set` populates the cache; a subsequent `get`
  with the *same* limit/offset is a hit.
- A `get` with *different* limit/offset than anything cached is a miss.
- A cached entry expires after the configured TTL (proven against a real
  Redis via Testcontainers, short TTL + sleep, same pattern as
  `OrderHistoryCacheSuite`).
- `GET /catalogs` integration test (route level, in-memory store + a fake/real
  cache) proves: first call is a cache miss (hits the store), second call
  with identical params is a cache hit (store not called again), and the
  response body/`X-Total-Count` are identical on both.
- Existing `GET /catalogs` 400 validation tests still pass unchanged (the
  cache sits *behind* validation, never in front of it).

## Out of Scope
- Cache invalidation on write (explicitly TTL-only, matching order-service).
- Caching any endpoint other than `GET /catalogs` (single-resource `GET
  /catalogs/{id}` stays uncached).
- A shared/normalized cache representation that avoids per-page duplication
  (e.g. caching individual catalogs once and composing pages from them) —
  out of scope; each distinct (limit, offset) page is cached independently
  and redundantly, same simplicity trade-off order-service made.

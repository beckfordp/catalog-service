# Product Guidelines — catalog-service

## Code & engineering conventions
Canonical source: [`development-guidelines.md`](../development-guidelines.md)
(pure-FP, tagless-final, ADT error modeling, no partial functions, no
null/var, explicit DI, exhaustive pattern matching). This file doesn't
restate those — keep following them as the service grows.

## API design conventions
- Plain resource JSON responses, standard HTTP status codes — no response
  envelope (e.g. no JSON:API)
- Errors: small JSON error object, status mapped 1:1 from the domain error
  ADT (`CatalogError`) — no catch-all exception translator, one explicit
  `errorOut` mapping per case
- REST paths pluralized (`/catalogs`, `/catalogs/{id}`) even though the
  DB/table name is singular (`catalog`)
- PATCH = partial update; PUT = idempotent full-replace (same client-writable
  fields as PATCH — `sku` is create-only, excluded from both)
- One dedicated Postgres database per service (`catalog`), named after the
  domain — never read/write another service's database directly;
  cross-service references are logical only (snapshot values), never a real
  FK
- Swagger/OpenAPI docs generated from the same tapir endpoint definitions as
  the real routes (`purerest.docs.Docs`) — stays in sync by construction
- Read-heavy traffic (US-1.1 browse/list) is the intended target for the
  Redis read-through cache (US-1.2) — cache reads, not writes; invalidate or
  TTL-expire on create/update/delete

## Testing
- scalafmt + munit + munit-cats-effect; Testcontainers-backed Postgres for
  integration tests, no manual local Postgres needed
- `sbt scalafmtCheck test` before every commit (CI enforces the same)

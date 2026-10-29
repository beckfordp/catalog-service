# TD-15.6 — Cross-repo consistency hardening (catalog-service)

## Overview

Catalog-service slice of gluon TD-15: typed `CatalogId` at the boundary,
ScalaCheck, and narrower effect bounds. No Kafka contract changes.

## Functional Requirements

1. **Typed ids** — opaque `CatalogId` parsed once at the route boundary; store
   get/update/delete take `CatalogId`; malformed → 400.
2. **Tests** — `munit-scalacheck` + id/list properties.
3. **Effect constraints** — `Cors`/`HealthRoutes`/route handlers narrowed where
   possible.

## Acceptance Criteria

- Malformed catalog id → 400; no raw UUID parsing in stores.
- Full suite + scalafmt green; coverage >80%.

## Out of Scope

- No config/Kafka changes (catalog has no AsyncAPI routes); other services.

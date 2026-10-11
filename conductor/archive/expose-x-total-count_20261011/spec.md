# Spec: Expose X-Total-Count via CORS

## Overview
`Cors.scala`'s middleware (added in `cors_20261010`) doesn't call `.withExposeHeadersIn(...)`, so http4s's CORS middleware only exposes the small CORS-safelisted header set to browser JS by default — not the custom `X-Total-Count` header `GET /catalogs` sends for pagination. Found via a gshop bug report ("Load more" button missing on the Catalog screen): confirmed live that `curl` sees `X-Total-Count: 100`, but the same request from a real browser via `fetch()` reports it as `null` and omits it entirely from `response.headers.keys()`. gshop's `catalogClient.list()` falls back to `items.length` when the header is missing, making `total === items.length` on every page — so Catalog's "Load more" button never rendered, even though 100 items exist.

## Functional Requirements
- `Cors.middleware` adds `.withExposeHeadersIn(Set(ci"X-Total-Count"))` to its policy chain.
- No other change to the CORS policy (origins/methods/allow-headers/credentials all stay as `cors_20261010` set them).

## Acceptance Criteria
- [x] `CorsSuite` gets a new test: a cross-origin `GET /catalogs` response includes `Access-Control-Expose-Headers` containing `X-Total-Count`.
- [x] Live: a real browser `fetch()` against `GET /catalogs` can read `X-Total-Count` via `response.headers.get(...)`.
- [x] Full `sbt test` suite stays green (77/77).

## Out of Scope
- Any other CORS policy change.
- The same gap in other services — none of them currently send a custom header gshop reads, so not applicable today.

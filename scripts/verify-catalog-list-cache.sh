#!/usr/bin/env bash
set -euo pipefail

# Verifies US-1.2's Phase 2 (CatalogListCache) end-to-end against the real
# docker-compose Redis instance (not Testcontainers) - confirms the exact
# key format and TTL mechanics CatalogListCache relies on actually work
# against a live Redis, complementing CatalogListCacheSuite's Scala-level
# tests. CatalogListCache isn't wired into GET /catalogs yet (that's
# Phase 3), so this drives Redis directly via redis-cli rather than HTTP.
#
# Usage: ./scripts/verify-catalog-list-cache.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FAILED=0

cleanup() {
  echo
  echo "Cleaning up..."
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "   OK: ${label} (got ${actual})"
  else
    echo "   FAIL: ${label} - expected ${expected}, got ${actual}" >&2
    FAILED=1
  fi
}

redis_cli() {
  docker compose exec -T redis redis-cli "$@" | tr -d '\r'
}

echo "1. docker compose up -d (fresh volumes, redis)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: redis starting"

echo
echo "2. Setting catalog-list:20:0 with a 2s TTL, matching CatalogListCache's key format and setEx usage..."
redis_cli set "catalog-list:20:0" '{"catalogs":[],"total":0}' EX 2 >/dev/null
immediate="$(redis_cli get "catalog-list:20:0")"
check "immediate GET returns the cached JSON" '{"catalogs":[],"total":0}' "$immediate"

echo
echo "3. A different (limit, offset) key is a miss, proving key isolation per page..."
different_key="$(redis_cli get "catalog-list:5:10")"
check "GET catalog-list:5:10 is nil" "" "$different_key"

echo
echo "4. Waiting past the 2s TTL, then confirming the key expired..."
sleep 3
expired="$(redis_cli get "catalog-list:20:0")"
check "GET after TTL expiry is nil" "" "$expired"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

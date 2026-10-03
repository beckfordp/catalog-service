#!/usr/bin/env bash
set -euo pipefail

# Verifies US-1.2's Phase 3 (cache-aside wiring) end-to-end: brings up
# Postgres + Redis + a real sbt run instance with a short cache TTL, drives
# real GET /catalogs traffic through it, and inspects Redis directly (via its
# own redis-cli, not the application) to confirm the cache is genuinely
# populated, genuinely stale on write (TTL-only freshness), and genuinely
# expires - not just that CatalogRoutesSuite's counting-store tests pass.
#
# Usage: ./scripts/verify-catalog-list-cache-endpoint.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FAILED=0
SBT_PID=""

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "$SBT_PID" ] && kill "$SBT_PID" >/dev/null 2>&1 || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 90); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: catalog-service did not become ready in time." >&2
  return 1
}

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "   OK: ${label} (got ${actual})"
  else
    echo "   FAIL: ${label} - expected ${expected}, got ${actual}" >&2
    FAILED=1
  fi
}

redis_get() {
  docker compose exec -T redis redis-cli get "$1" | tr -d '\r'
}

create_catalog() {
  curl -s -X POST http://localhost:8080/catalogs \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$1\",\"description\":\"desc\",\"priceCents\":999,\"sku\":\"$2\"}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])"
}

echo "1. docker compose up -d (fresh volumes, postgres + redis)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres + redis starting"

echo
echo "2. Starting catalog-service (sbt run) with a 3s list-cache TTL..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
CATALOG_LIST_CACHE_TTL_SECONDS=3 sbt run >/tmp/catalog-service-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: catalog-service ready on :8080"

echo
echo "3. Creating one catalog, then GET /catalogs (defaults) twice..."
first_id="$(create_catalog "Widget" "sku-1")"
first_response="$(curl -s http://localhost:8080/catalogs)"
second_response="$(curl -s http://localhost:8080/catalogs)"
check "first and second GET /catalogs bodies are identical" "$first_response" "$second_response"

echo
echo "4. Inspecting Redis directly - the cache key should now hold the page..."
cached_raw="$(redis_get "catalog-list:20:0")"
cached_ids="$(echo "$cached_raw" | python3 -c "import json,sys; print(','.join(c['id'] for c in json.load(sys.stdin)['catalogs']))" 2>/dev/null || echo "<unparseable>")"
check "Redis cache key holds the created catalog" "$first_id" "$cached_ids"

echo
echo "5. Creating a second catalog - GET /catalogs still returns the stale cached page (TTL-only freshness)..."
second_id="$(create_catalog "Gadget" "sku-2")"
stale_response="$(curl -s http://localhost:8080/catalogs)"
check "GET /catalogs still returns the stale (1-item) cached page" "$first_response" "$stale_response"

echo
echo "6. Waiting past the 3s TTL, then confirming Redis expired the key and the next GET sees both catalogs..."
sleep 4
expired_raw="$(redis_get "catalog-list:20:0")"
check "Redis cache key expired" "" "$expired_raw"
fresh_response="$(curl -s http://localhost:8080/catalogs)"
fresh_ids="$(echo "$fresh_response" | python3 -c "import json,sys; print(','.join(c['id'] for c in json.load(sys.stdin)))")"
check "GET /catalogs now returns both catalogs newest-first" "${second_id},${first_id}" "$fresh_ids"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

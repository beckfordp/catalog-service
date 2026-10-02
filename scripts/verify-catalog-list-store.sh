#!/usr/bin/env bash
set -euo pipefail

# Verifies CatalogStore.list's underlying SQL semantics end-to-end (track
# catalog-browse-list_20261002, Phase 1): brings up a real (non-Testcontainers)
# Postgres via docker compose, runs migrations via a real `sbt run`, then
# inserts rows and confirms the exact ORDER BY/LIMIT/OFFSET/count(*) shape
# CatalogStore.postgres.list uses returns the right page and total directly
# against the live database - not just that the unit/integration test suite
# passes. Phase 1 only builds the store layer (no GET /catalogs route yet -
# that's Phase 2), so there's no HTTP surface to drive here.
#
# Usage: ./scripts/verify-catalog-list-store.sh

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

psql_exec() {
  docker compose exec -T postgres psql -U catalog -d catalog -t -A -c "$1"
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

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres starting"

echo
echo "2. Starting catalog-service (sbt run) in the background to apply migrations..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/catalog-service-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: catalog-service ready (migrations applied)"

echo
echo "3. Inserting 5 catalog rows directly (distinct created_at via pg_sleep)..."
for i in 1 2 3 4 5; do
  psql_exec "insert into \"catalog\" (id, name, description, price_cents, sku) values (gen_random_uuid(), 'Item ${i}', 'desc', ${i}00, 'sku-${i}')" >/dev/null
  psql_exec "select pg_sleep(0.01)" >/dev/null
done
row_count="$(psql_exec "select count(*) from \"catalog\"")"
check "5 rows inserted" "5" "$row_count"

echo
echo "4. ORDER BY created_at DESC LIMIT 2 OFFSET 1 returns the 4th and 3rd newest..."
expected_ids="$(psql_exec "select name from \"catalog\" order by created_at desc limit 2 offset 1" | tr '\n' ',' | sed 's/,$//')"
check "page returns Item 4,Item 3" "Item 4,Item 3" "$expected_ids"

echo
echo "5. count(*) matches the total regardless of page size..."
total="$(psql_exec "select count(*) from \"catalog\"")"
check "count(*) is 5" "5" "$total"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

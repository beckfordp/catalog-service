#!/usr/bin/env bash
set -euo pipefail

# Verifies GET /catalogs end-to-end (track catalog-browse-list_20261002,
# Phase 2): brings up Postgres + a real sbt run instance, drives real HTTP
# traffic through it to confirm pagination, ordering, the X-Total-Count
# header, and the 400 validation cases - not just that the test suite
# passes.
#
# Usage: ./scripts/verify-catalog-list-endpoint.sh

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

create_catalog() {
  curl -s -X POST http://localhost:8080/catalogs \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$1\",\"description\":\"desc\",\"priceCents\":999,\"sku\":\"$2\"}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])"
}

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres starting"

echo
echo "2. Starting catalog-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/catalog-service-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: catalog-service ready on :8080"

echo
echo "3. GET /catalogs on an empty table returns 200, [], X-Total-Count: 0..."
empty_response="$(curl -s -D - -o /tmp/catalog-list-empty-body.json http://localhost:8080/catalogs)"
empty_status="$(echo "$empty_response" | head -1 | tr -d '\r' | awk '{print $2}')"
empty_total="$(echo "$empty_response" | grep -i '^x-total-count:' | tr -d '\r' | awk '{print $2}')"
check "GET /catalogs (empty) returns 200" "200" "$empty_status"
check "GET /catalogs (empty) body is []" "[]" "$(cat /tmp/catalog-list-empty-body.json)"
check "GET /catalogs (empty) X-Total-Count is 0" "0" "$empty_total"

echo
echo "4. Creating 5 catalogs..."
ids=()
for i in 1 2 3 4 5; do
  ids+=("$(create_catalog "Item ${i}" "sku-${i}")")
  sleep 0.05
done
echo "   created: ${ids[*]}"

echo
echo "5. GET /catalogs (defaults) returns all 5, newest-first, X-Total-Count: 5..."
list_response="$(curl -s -D - -o /tmp/catalog-list-body.json http://localhost:8080/catalogs)"
list_status="$(echo "$list_response" | head -1 | tr -d '\r' | awk '{print $2}')"
list_total="$(echo "$list_response" | grep -i '^x-total-count:' | tr -d '\r' | awk '{print $2}')"
returned_ids="$(python3 -c "import json; print(','.join(c['id'] for c in json.load(open('/tmp/catalog-list-body.json'))))")"
expected_ids="${ids[4]},${ids[3]},${ids[2]},${ids[1]},${ids[0]}"
check "GET /catalogs returns 200" "200" "$list_status"
check "GET /catalogs X-Total-Count is 5" "5" "$list_total"
check "GET /catalogs returns all 5 newest-first" "$expected_ids" "$returned_ids"

echo
echo "6. GET /catalogs?limit=2&offset=1 returns the 4th and 3rd newest..."
page_response="$(curl -s "http://localhost:8080/catalogs?limit=2&offset=1")"
page_ids="$(echo "$page_response" | python3 -c "import json,sys; print(','.join(c['id'] for c in json.load(sys.stdin)))")"
expected_page="${ids[3]},${ids[2]}"
check "page returns the 4th and 3rd newest" "$expected_page" "$page_ids"

echo
echo "7. Invalid pagination params are rejected with 400..."
limit_zero="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/catalogs?limit=0")"
check "limit=0 returns 400" "400" "$limit_zero"
limit_over="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/catalogs?limit=101")"
check "limit=101 returns 400" "400" "$limit_over"
offset_negative="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/catalogs?offset=-1")"
check "offset=-1 returns 400" "400" "$offset_negative"

echo
echo "8. The endpoint is documented..."
docs="$(curl -s -L http://localhost:8080/docs/docs.yaml)"
check "docs.yaml mentions /catalogs" "1" "$(echo "$docs" | grep -c '/catalogs' | head -1 | awk '{print ($1>0)?1:0}')"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

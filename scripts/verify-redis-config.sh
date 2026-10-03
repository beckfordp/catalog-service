#!/usr/bin/env bash
set -euo pipefail

# Verifies US-1.2's Phase 1 (tech stack + local infra) end-to-end: brings up
# Postgres + Redis via docker compose, starts a real sbt run instance, and
# confirms both the service and Redis itself are reachable with the new
# RedisConfig loaded - not just that the config unit tests pass.
#
# Usage: ./scripts/verify-redis-config.sh

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

echo "1. docker compose up -d (fresh volumes, postgres + redis)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres + redis starting"

echo
echo "2. Confirming Redis itself is reachable via redis-cli..."
redis_ping="$(docker compose exec -T redis redis-cli ping | tr -d '\r')"
check "redis-cli ping" "PONG" "$redis_ping"

echo
echo "3. Starting catalog-service (sbt run) with the shipped RedisConfig defaults..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/catalog-service-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: catalog-service ready on :8080 (RedisConfig loaded without error)"

echo
echo "4. Health and readiness still 200 (Postgres-only readiness check unaffected by the new Redis config)..."
health="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health)"
check "GET /health returns 200" "200" "$health"
ready="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health/ready)"
check "GET /health/ready returns 200" "200" "$ready"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

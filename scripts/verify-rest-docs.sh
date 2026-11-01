#!/usr/bin/env bash
set -euo pipefail

# Verifies the REST docs convention end-to-end (track rest-docs_20261101,
# gluon TD-20.7): brings up Postgres + Redis and a real sbt run instance,
# fetches the served OpenAPI spec at /docs/docs.yaml, and checks every
# endpoint's summary/description/tag, its parameter and header descriptions +
# examples, DTO field descriptions and body examples, and that each endpoint
# lists only the error statuses it can return - not just that
# CatalogDocsSuite's in-process Docs.undocumented check passes.
#
# Requires: sbt, Docker, python3 with PyYAML, GITHUB_TOKEN/GITHUB_ACTOR set
# (purerestlib resolution).
#
# Usage: ./scripts/verify-rest-docs.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SBT_PID=""
SPEC="$(mktemp)"

# Postgres/Redis are published on non-default host ports so the check can run
# alongside other services' compose stacks (e.g. fulfilment-service already
# binds 5432). Override via CATALOG_VERIFY_POSTGRES_PORT/_REDIS_PORT.
POSTGRES_HOST_PORT="${CATALOG_VERIFY_POSTGRES_PORT:-55432}"
REDIS_HOST_PORT="${CATALOG_VERIFY_REDIS_PORT:-56379}"
OVERRIDE="$(mktemp)"
cat >"$OVERRIDE" <<YAML
services:
  postgres:
    ports: !override
      - "${POSTGRES_HOST_PORT}:5432"
  redis:
    ports: !override
      - "${REDIS_HOST_PORT}:6379"
YAML
COMPOSE=(docker compose -f docker-compose.yml -f "$OVERRIDE")

cleanup() {
  echo
  echo "Cleaning up..."
  if [ -n "$SBT_PID" ]; then
    pkill -9 -P "$SBT_PID" >/dev/null 2>&1 || true
    kill -9 "$SBT_PID" >/dev/null 2>&1 || true
  fi
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
  rm -f "$SPEC" "$OVERRIDE"
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 120); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: catalog-service did not become ready in time." >&2
  return 1
}

echo "1. docker compose up -d (fresh volumes; host ports $POSTGRES_HOST_PORT/$REDIS_HOST_PORT)..."
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d
echo "   OK: postgres/redis starting"

echo
echo "2. Starting catalog-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
POSTGRES_PORT="$POSTGRES_HOST_PORT" REDIS_URI="redis://localhost:$REDIS_HOST_PORT" \
  sbt run </dev/null >/tmp/catalog-service-verify-rest-docs.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: catalog-service ready on :8080"

echo
echo "3. GET /docs/docs.yaml..."
status="$(curl -s -o "$SPEC" -w '%{http_code}' http://localhost:8080/docs/docs.yaml)"
[ "$status" = "200" ] || { echo "FAIL: /docs/docs.yaml returned $status" >&2; exit 1; }
echo "   OK: served (200)"

echo
echo "4. Checking the spec..."
python3 -I - "$SPEC" <<'PY'
import sys, yaml

spec = yaml.safe_load(open(sys.argv[1]))
paths, schemas = spec["paths"], spec["components"]["schemas"]
failed = False

def check(label, ok):
    global failed
    print(("   OK: " if ok else "   FAIL: ") + label, file=sys.stdout if ok else sys.stderr)
    failed |= not ok

# Statuses each endpoint may list (success + its narrowed errors; tapir adds
# 400 for undecodable inputs on its own).
allowed = {
    ("/catalogs", "post"): {"201", "400"},
    ("/catalogs", "get"): {"200", "400"},
    ("/catalogs/{id}", "get"): {"200", "400", "404"},
    ("/catalogs/{id}", "patch"): {"200", "400", "404"},
    ("/catalogs/{id}", "put"): {"200", "400", "404"},
    ("/catalogs/{id}", "delete"): {"204", "400", "404"},
    ("/health", "get"): {"200"},
    ("/health/ready", "get"): {"200", "503"},
}
required = {
    ("/catalogs", "post"): {"400"},
    ("/catalogs", "get"): {"400"},
    ("/catalogs/{id}", "get"): {"404"},
    ("/catalogs/{id}", "patch"): {"400", "404"},
    ("/catalogs/{id}", "put"): {"400", "404"},
    ("/catalogs/{id}", "delete"): {"404"},
    ("/health/ready", "get"): {"503"},
}
for (path, method), statuses in allowed.items():
    op = paths.get(path, {}).get(method)
    name = f"{method.upper()} {path}"
    if op is None:
        check(f"{name} documented", False)
        continue
    check(f"{name} has summary/description/tag",
          bool(op.get("summary")) and bool(op.get("description")) and bool(op.get("tags")))
    for p in op.get("parameters", []):
        check(f"{name} parameter '{p['name']}' described with example",
              bool(p.get("description")) and ("example" in p or "example" in p.get("schema", {})))
    responses = op.get("responses", {})
    check(f"{name} lists only {sorted(statuses)} (got {sorted(responses)})",
          set(responses) <= statuses)
    check(f"{name} lists {sorted(required.get((path, method), set()))}",
          required.get((path, method), set()) <= set(responses))
    for code, r in responses.items():
        if code.startswith("2"):
            continue
        check(f"{name} {code} described", bool(r.get("description")))

check("GET /catalogs tagged 'catalogs'", paths["/catalogs"]["get"].get("tags") == ["catalogs"])
check("POST /catalogs tagged 'catalogs'", paths["/catalogs"]["post"].get("tags") == ["catalogs"])
check("GET /catalogs/{id} tagged 'catalogs'", paths["/catalogs/{id}"]["get"].get("tags") == ["catalogs"])
check("GET /health tagged 'health'", paths["/health"]["get"].get("tags") == ["health"])
check("GET /health/ready tagged 'health'", paths["/health/ready"]["get"].get("tags") == ["health"])

header = paths["/catalogs"]["get"]["responses"]["200"].get("headers", {}).get("X-Total-Count", {})
check("GET /catalogs X-Total-Count described with example",
      bool(header.get("description"))
      and (header.get("example") is not None
           or header.get("schema", {}).get("example") is not None))

def body(op, code=None):
    if code is None:
        return op.get("requestBody", {}).get("content", {})
    return op.get("responses", {}).get(code, {}).get("content", {})

def has_example(content):
    return any("example" in c or "examples" in c for c in content.values())

def example(content):
    for c in content.values():
        if "example" in c:
            return c["example"]
    return None

post, list_op = paths["/catalogs"]["post"], paths["/catalogs"]["get"]
get_one = paths["/catalogs/{id}"]["get"]

check("POST /catalogs request body has an example", has_example(body(post)))
check("POST /catalogs 201 response body has an example", has_example(body(post, "201")))
check("GET /catalogs 200 response body has an example (list)",
      has_example(body(list_op, "200")) and isinstance(example(body(list_op, "200")), list))
check("GET /catalogs/{id} 200 response body has an example",
      has_example(body(get_one, "200")))
check("PATCH /catalogs/{id} request body has an example",
      has_example(body(paths["/catalogs/{id}"]["patch"])))
check("PUT /catalogs/{id} request body has an example",
      has_example(body(paths["/catalogs/{id}"]["put"])))

check("POST /catalogs 400 has the invalid-catalog example",
      example(body(post, "400")) == {"error": "priceCents must be at least 0, got -1"})
check("GET /catalogs 400 has the pagination example",
      example(body(list_op, "400")) == {"error": "Invalid pagination parameters"})
check("GET /catalogs/{id} 404 has the not-found example",
      example(body(get_one, "404")) == {"error": "Catalog not found"})
check("GET /health/ready 503 has no body but is described",
      "content" not in paths["/health/ready"]["get"]["responses"]["503"]
      and bool(paths["/health/ready"]["get"]["responses"]["503"].get("description")))

for name in ["CreateCatalogRequest", "UpdateCatalogRequest", "CatalogResponse", "ErrorResponse"]:
    schema = schemas.get(name, {})
    props = schema.get("properties", {})
    missing = [f for f, s in props.items() if not s.get("description")]
    check(f"schema {name} described" + (f" (missing: {missing})" if missing else ""),
          bool(schema.get("description")) and bool(props) and not missing)
    exemplified = bool(schema.get("example")) or (
        bool(props) and all(
            "example" in s or "examples" in s or s.get("enum")
            for s in props.values()
        )
    )
    check(f"schema {name} exemplified", exemplified)

sys.exit(1 if failed else 0)
PY

echo
echo "All checks passed."

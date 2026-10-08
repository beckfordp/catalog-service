#!/usr/bin/env bash
set -euo pipefail

# Seeds catalog-service with a small luxury-watch demo catalog via its own
# public POST /catalogs endpoint (track seed-watches_20261008) - so gshop's
# US-1 Catalog screen (still backlog-only as of this writing) has realistic
# data to render against once wired up. SKUs follow an invented
# `watch-<brand>-<model-slug>` convention; gshop's src/data/watchImages.ts
# doesn't exist yet, so exact SKU matching is deferred until that file is
# built.
#
# Assumes catalog-service is already running and reachable (OrbStack k8s,
# or local docker compose + sbt run) - this script does not start it.
#
# Usage: ./scripts/seed-watches.sh
# Env:   CATALOG_SERVICE_URL (default http://localhost:8080)

BASE_URL="${CATALOG_SERVICE_URL:-http://localhost:8080}"

# name|description|priceCents|sku
WATCHES=(
  "Rolex Submariner|Iconic diver's watch, 41mm stainless steel case|950000|watch-rolex-submariner"
  "Omega Speedmaster Moonwatch|Legendary chronograph worn on the moon|720000|watch-omega-speedmaster"
  "Patek Philippe Nautilus|Luxury sports watch with a blue dial|3500000|watch-patek-nautilus"
  "Audemars Piguet Royal Oak|Octagonal bezel, tapisserie dial|2850000|watch-ap-royal-oak"
  "Tag Heuer Carrera|Classic motorsport-inspired chronograph|430000|watch-tagheuer-carrera"
  "Breitling Navitimer|Pilot's watch with slide-rule bezel|890000|watch-breitling-navitimer"
  "IWC Portugieser|Clean dial, large case, in-house movement|1240000|watch-iwc-portugieser"
  "Cartier Santos|Square-cased watch with exposed screws|785000|watch-cartier-santos"
  "Jaeger-LeCoultre Reverso|Reversible rectangular case, Art Deco heritage|1120000|watch-jlc-reverso"
  "Vacheron Constantin Overseas|Integrated-bracelet sports watch|2400000|watch-vc-overseas"
)

created=0
skipped=0
failed=0

existing_skus() {
  curl -s "${BASE_URL}/catalogs?limit=100" \
    | python3 -c "import json,sys; print('\n'.join(c['sku'] for c in json.load(sys.stdin)))"
}

EXISTING_SKUS="$(existing_skus)"

echo "Seeding luxury-watch catalog data into ${BASE_URL}..."
echo

for row in "${WATCHES[@]}"; do
  IFS='|' read -r name description price_cents sku <<<"$row"

  if grep -qxF "$sku" <<<"$EXISTING_SKUS"; then
    echo "  SKIP: ${sku} (already exists)"
    skipped=$((skipped + 1))
    continue
  fi

  payload="$(python3 -c "import json,sys; print(json.dumps({'name': sys.argv[1], 'description': sys.argv[2], 'priceCents': int(sys.argv[3]), 'sku': sys.argv[4]}))" "$name" "$description" "$price_cents" "$sku")"

  http_status="$(curl -s -o /tmp/seed-watches-response.json -w '%{http_code}' \
    -X POST "${BASE_URL}/catalogs" \
    -H "Content-Type: application/json" \
    -d "$payload")"

  if [ "$http_status" = "201" ]; then
    catalog_id="$(python3 -c "import json; print(json.load(open('/tmp/seed-watches-response.json'))['id'])")"
    echo "  CREATED: ${sku} -> ${catalog_id}"
    created=$((created + 1))
  else
    echo "  FAILED: ${sku} (HTTP ${http_status})" >&2
    failed=$((failed + 1))
  fi
done

echo
echo "Summary: ${created} created, ${skipped} skipped, ${failed} failed."

if [ "$failed" -gt 0 ]; then
  exit 1
fi

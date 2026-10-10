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
# Also backfills inventory-service with a matching stock record
# (quantityAvailable: 10) for any sku that doesn't have one yet - catalog
# and inventory are separate services/databases with no FK, so they drift
# out of sync on their own; this keeps them in sync from one script/one
# source of truth (the WATCHES list below) instead of a second script
# duplicating it. Idempotent either way - safe to rerun.
#
# Assumes catalog-service and inventory-service are already running and
# reachable (OrbStack k8s, or local docker compose + sbt run) - this
# script does not start them.
#
# Usage: ./scripts/seed-watches.sh
# Env:   CATALOG_SERVICE_URL (default http://localhost:8080)
#        INVENTORY_SERVICE_URL (default http://inventory-service.gluon-local.svc.cluster.local:8080)

BASE_URL="${CATALOG_SERVICE_URL:-http://localhost:8080}"
INVENTORY_BASE_URL="${INVENTORY_SERVICE_URL:-http://inventory-service.gluon-local.svc.cluster.local:8080}"

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

  # 90 more real watches added by gshop's boutique-redesign_20261010 track
  # (dark-luxury restyle + catalog growth 10 -> 100), same name|description|
  # priceCents|sku format and ~$4,300-$35,000 price band as the original 10.
  "Rolex Daytona|Legendary racing chronograph, Oystersteel case|2200000|watch-rolex-daytona"
  "Rolex GMT-Master II|Dual-time travel watch, Jubilee bracelet|1850000|watch-rolex-gmt-master-ii"
  "Omega Seamaster Diver 300M|Master Chronometer dive watch, wave-pattern dial|890000|watch-omega-seamaster-diver-300m"
  "Omega Constellation|Observatory-inspired dress watch with claws|680000|watch-omega-constellation"
  "Patek Philippe Calatrava|Understated round dress watch, hobnail bezel|3200000|watch-patek-calatrava"
  "Patek Philippe Aquanaut|Tropical-strap sports watch, embossed dial|3400000|watch-patek-aquanaut"
  "Audemars Piguet Code 11.59|Curved sapphire case, contemporary dress watch|3100000|watch-ap-code-1159"
  "Audemars Piguet Royal Oak Offshore|Oversized sports chronograph|3350000|watch-ap-royal-oak-offshore"
  "Tag Heuer Monaco|Square-cased racing chronograph|550000|watch-tagheuer-monaco"
  "Tag Heuer Aquaracer|Professional dive watch, ceramic bezel|480000|watch-tagheuer-aquaracer"
  "Breitling Superocean|Rotating dive bezel, Super-LumiNova markers|520000|watch-breitling-superocean"
  "Breitling Chronomat|Rouleaux-bracelet pilot's chronograph|780000|watch-breitling-chronomat"
  "IWC Pilot's Watch Mark XX|Clean aviation dial, soft-iron inner case|620000|watch-iwc-pilots-mark-xx"
  "IWC Ingenieur|Integrated-bracelet engineering icon|980000|watch-iwc-ingenieur"
  "Cartier Tank|Rectangular case, Roman numeral dial|680000|watch-cartier-tank"
  "Cartier Ballon Bleu|Curved case with guarded crown|750000|watch-cartier-ballon-bleu"
  "Jaeger-LeCoultre Master Control|Three-hand dress watch, in-house caliber|920000|watch-jlc-master-control"
  "Jaeger-LeCoultre Polaris|Vintage-inspired dive watch|1050000|watch-jlc-polaris"
  "Vacheron Constantin Patrimony|Minimalist dress watch, Geneva hallmark|2600000|watch-vc-patrimony"
  "Vacheron Constantin Fiftysix|Octagonal bezel, self-winding movement|1950000|watch-vc-fiftysix"
  "Panerai Luminor|Cushion case, patented crown-protecting bridge|850000|watch-panerai-luminor"
  "Panerai Radiomir|Wire-lug case, vintage cushion silhouette|790000|watch-panerai-radiomir"
  "Hublot Big Bang|Fusion of materials, exposed chronograph movement|1450000|watch-hublot-big-bang"
  "Hublot Classic Fusion|Slim case, interchangeable strap system|960000|watch-hublot-classic-fusion"
  "Zenith Chronomaster|High-frequency El Primero chronograph|920000|watch-zenith-chronomaster"
  "Zenith Defy|Skeletonized futuristic sports watch|870000|watch-zenith-defy"
  "Breguet Classique|Guilloche dial, coin-edge case|2850000|watch-breguet-classique"
  "Breguet Marine|Fluted bezel, nautical heritage|1750000|watch-breguet-marine"
  "Longines Master Collection|Moonphase dress watch, railway dial|480000|watch-longines-master-collection"
  "Longines Spirit|Pilot-inspired chronometer, brushed steel case|520000|watch-longines-spirit"
  "Tudor Black Bay|Snowflake hands, heritage dive watch|430000|watch-tudor-black-bay"
  "Tudor Pelagos|Titanium dive watch, helium escape valve|470000|watch-tudor-pelagos"
  "Grand Seiko Snowflake|Textured dial inspired by fresh snow|580000|watch-grandseiko-snowflake"
  "Grand Seiko Spring Drive|Glide-motion seconds hand, zaratsu polishing|720000|watch-grandseiko-spring-drive"
  "Oris Aquis|Rotating bezel, 300m dive rating|450000|watch-oris-aquis"
  "Oris Big Crown|Pilot's watch heritage, oversized crown|440000|watch-oris-big-crown"
  "Montblanc 1858|Vintage military styling, bronze case option|510000|watch-montblanc-1858"
  "Montblanc Star Legacy|Classic round case, railway-track minute ring|530000|watch-montblanc-star-legacy"
  "Chopard Mille Miglia|Racing chronograph, tachymeter bezel|680000|watch-chopard-mille-miglia"
  "Chopard L.U.C|In-house movement, Geneva Seal dress watch|1450000|watch-chopard-luc"
  "Piaget Polo|Integrated bracelet, slim sports case|1250000|watch-piaget-polo"
  "Piaget Altiplano|Ultra-thin dress watch|1150000|watch-piaget-altiplano"
  "Girard-Perregaux Laureato|Octagonal bezel, integrated bracelet|980000|watch-gp-laureato"
  "Girard-Perregaux 1966|Classic round case, slim profile|720000|watch-gp-1966"
  "Baume & Mercier Clifton|Vintage-inspired dress watch|460000|watch-baumemercier-clifton"
  "Baume & Mercier Riviera|Cushion case sports watch|500000|watch-baumemercier-riviera"
  "Bell & Ross BR 03|Square aviation-instrument case|480000|watch-bellross-br03"
  "Bell & Ross BR V2|Round pilot's chronograph|560000|watch-bellross-brv2"
  "Bulgari Octo Finissimo|Faceted ultra-thin case|1650000|watch-bulgari-octo-finissimo"
  "Bulgari Serpenti|Coiled bracelet-case dress watch|1350000|watch-bulgari-serpenti"
  "Franck Muller Vanguard|Curved tonneau case, bold numerals|1250000|watch-franckmuller-vanguard"
  "Franck Muller Casablanca|Art Deco tonneau dress watch|1150000|watch-franckmuller-casablanca"
  "Ulysse Nardin Marine|Nautical chronometer heritage|980000|watch-ulyssenardin-marine"
  "Ulysse Nardin Diver|Rotating bezel, lume-filled dial|720000|watch-ulyssenardin-diver"
  "A. Lange & Sohne Saxonia|Outsized date, German dress watch|2650000|watch-alange-saxonia"
  "A. Lange & Sohne 1815|Classic railway-track dial|2450000|watch-alange-1815"
  "Blancpain Fifty Fathoms|Pioneering modern dive watch|1850000|watch-blancpain-fifty-fathoms"
  "Blancpain Villeret|Ultra-slim complication dress watch|2150000|watch-blancpain-villeret"
  "Glashutte Original Senator|German precision dress watch|1450000|watch-glashutte-senator"
  "Glashutte Original PanoMatic|Off-center panorama date display|1650000|watch-glashutte-panomatic"
  "H. Moser & Cie Pioneer|Fume dial, minimalist dress watch|1250000|watch-moser-pioneer"
  "H. Moser & Cie Endeavour|Hand-wound, no-logo dial philosophy|1150000|watch-moser-endeavour"
  "Nomos Glashutte Tangente|Bauhaus-inspired minimalist dial|480000|watch-nomos-tangente"
  "Nomos Glashutte Club|Sporty round case, clean dial|450000|watch-nomos-club"
  "Rado True|High-tech ceramic case construction|430000|watch-rado-true"
  "Rado Captain Cook|Vintage dive watch reissue|460000|watch-rado-captain-cook"
  "Sinn 556|Tool-watch simplicity, tegimented steel|440000|watch-sinn-556"
  "Sinn 104|Pilot's chronograph, oil-filled models available|470000|watch-sinn-104"
  "Zodiac Super Sea Wolf|Compressor-case dive watch heritage|450000|watch-zodiac-super-sea-wolf"
  "Zodiac Olympos|Integrated-bracelet sports watch|470000|watch-zodiac-olympos"
  "Movado Museum|Dot dial designed by Nathan George Horwitt|440000|watch-movado-museum"
  "Movado Bold|Minimalist sunray dial, bold case|430000|watch-movado-bold"
  "Frederique Constant Classics|Heart-beat open dial dress watch|450000|watch-frederiqueconstant-classics"
  "Frederique Constant Highlife|Integrated-bracelet sports watch|470000|watch-frederiqueconstant-highlife"
  "Alpina Alpiner|Mountaineering-inspired field watch|440000|watch-alpina-alpiner"
  "Alpina Startimer|Pilot's watch, oversized crown|450000|watch-alpina-startimer"
  "Eberhard & Co Scafograf|Vintage dive watch reissue|460000|watch-eberhard-scafograf"
  "Eberhard & Co Extra-fort|Chronograph with railway minute track|480000|watch-eberhard-extrafort"
  "Perrelet Turbine|Rotating turbine rotor visible on dial|500000|watch-perrelet-turbine"
  "Perrelet First Class|Skeletonized aviation-inspired case|520000|watch-perrelet-first-class"
  "Corum Admiral|Nautical flag motifs, pennant-shaped indices|490000|watch-corum-admiral"
  "Corum Bubble|Oversized domed crystal case|460000|watch-corum-bubble"
  "Chronoswiss Regulator|Separated hour and minute sub-dials|530000|watch-chronoswiss-regulator"
  "Chronoswiss Timemaster|Fluted bezel, screw-down crown|500000|watch-chronoswiss-timemaster"
  "Maurice Lacroix Aikon|Integrated-bracelet steel sports watch|450000|watch-mauricelacroix-aikon"
  "Maurice Lacroix Masterpiece|Skeletonized hand-wound movement|520000|watch-mauricelacroix-masterpiece"
  "Raymond Weil Freelancer|Openworked automatic dress watch|440000|watch-raymondweil-freelancer"
  "Raymond Weil Tango|Sporty three-hand steel watch|430000|watch-raymondweil-tango"
  "Mido Ocean Star|Diver's watch with helium-safe construction|440000|watch-mido-ocean-star"
  "Mido Commander|Day-date dress watch, fluted bezel|450000|watch-mido-commander"
)

created=0
skipped=0
failed=0

inventory_created=0
inventory_skipped=0
inventory_failed=0

existing_skus() {
  curl -s "${BASE_URL}/catalogs?limit=100" \
    | python3 -c "import json,sys; print('\n'.join(c['sku'] for c in json.load(sys.stdin)))"
}

existing_inventory_skus() {
  curl -s "${INVENTORY_BASE_URL}/inventorys" \
    | python3 -c "import json,sys; print('\n'.join(i['sku'] for i in json.load(sys.stdin)))"
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

echo
echo "Backfilling inventory stock (quantityAvailable: 10) at ${INVENTORY_BASE_URL}..."
echo

EXISTING_INVENTORY_SKUS="$(existing_inventory_skus)"

for row in "${WATCHES[@]}"; do
  IFS='|' read -r name description price_cents sku <<<"$row"

  if grep -qxF "$sku" <<<"$EXISTING_INVENTORY_SKUS"; then
    echo "  SKIP: ${sku} (inventory already exists)"
    inventory_skipped=$((inventory_skipped + 1))
    continue
  fi

  inventory_payload="$(python3 -c "import json,sys; print(json.dumps({'sku': sys.argv[1], 'quantityAvailable': 10}))" "$sku")"

  inventory_http_status="$(curl -s -o /tmp/seed-watches-inventory-response.json -w '%{http_code}' \
    -X POST "${INVENTORY_BASE_URL}/inventorys" \
    -H "Content-Type: application/json" \
    -d "$inventory_payload")"

  if [ "$inventory_http_status" = "201" ]; then
    inventory_id="$(python3 -c "import json; print(json.load(open('/tmp/seed-watches-inventory-response.json'))['id'])")"
    echo "  CREATED: ${sku} -> ${inventory_id} (qty 10)"
    inventory_created=$((inventory_created + 1))
  else
    echo "  FAILED: ${sku} (HTTP ${inventory_http_status})" >&2
    inventory_failed=$((inventory_failed + 1))
  fi
done

echo
echo "Inventory summary: ${inventory_created} created, ${inventory_skipped} skipped, ${inventory_failed} failed."

if [ "$failed" -gt 0 ] || [ "$inventory_failed" -gt 0 ]; then
  exit 1
fi

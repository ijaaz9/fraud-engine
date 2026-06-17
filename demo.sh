#!/usr/bin/env bash
# demo.sh — Publish transactions to Kafka to demonstrate each fraud rule.
# Prerequisites: stack is running (docker-compose up --build) and the app is healthy.
#
# Usage:
#   chmod +x demo.sh
#   ./demo.sh

set -euo pipefail

CONTAINER="fraud-kafka"
BROKER="localhost:29092"   # internal listener inside the fraud-kafka container
TOPIC="transactions"
API="http://localhost:8080"

# ── helpers ───────────────────────────────────────────────────────────────────

publish() {
  local label="$1"
  local payload="$2"
  printf '  → %s\n' "$label"
  printf '%s\n' "$payload" | docker exec -i "$CONTAINER" \
    kafka-console-producer --bootstrap-server "$BROKER" --topic "$TOPIC" 2>/dev/null
  sleep 0.3
}

hr() { printf '\n%.0s─' {1..60}; printf '\n\n'; }

# ── wait for app ──────────────────────────────────────────────────────────────

printf 'Waiting for app to be healthy'
until curl -sf "$API/actuator/health" | grep -q '"status":"UP"' 2>/dev/null; do
  printf '.'
  sleep 2
done
echo ' ready.'

printf '\n'; printf '=%.0s' {1..60}; printf '\n'
echo '  Fraud Detection Demo — seeding Kafka topic'
printf '=%.0s' {1..60}; printf '\n\n'

# ── [1] High Amount ───────────────────────────────────────────────────────────

echo '[1/6] HIGH_AMOUNT — single transaction over $10,000'
publish 'demo-high-001  $15,000  London' \
  '{"transactionId":"demo-high-001","userId":"user-high","amount":15000.00,"merchant":"Luxury Watches Ltd","category":"LUXURY","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T10:00:00Z"}'
echo '      Expect: HIGH_AMOUNT  score 30  severity LOW'

hr

# ── [2] Duplicate Detection ───────────────────────────────────────────────────

echo '[2/6] DUPLICATE_TRANSACTION — same user/merchant/amount within 2 minutes'
publish 'demo-dup-001  original' \
  '{"transactionId":"demo-dup-001","userId":"user-dup","amount":250.00,"merchant":"ACME Coffee","category":"FOOD","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T10:01:00Z"}'
sleep 1
publish 'demo-dup-002  duplicate 30 s later' \
  '{"transactionId":"demo-dup-002","userId":"user-dup","amount":250.00,"merchant":"ACME Coffee","category":"FOOD","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T10:01:30Z"}'
echo '      Expect: demo-dup-002  DUPLICATE_TRANSACTION  score 25  severity LOW'

hr

# ── [3] Geo Anomaly ───────────────────────────────────────────────────────────

echo '[3/6] GEO_ANOMALY — location jump > 500 km  (London → New York, ~5,570 km, 6hrs → ~928 km/h)'
publish 'demo-geo-001  London' \
  '{"transactionId":"demo-geo-001","userId":"user-geo","amount":80.00,"merchant":"London Bookshop","category":"RETAIL","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T10:02:00Z"}'
sleep 1
publish 'demo-geo-002  New York  6 hours later' \
  '{"transactionId":"demo-geo-002","userId":"user-geo","amount":120.00,"merchant":"NYC Deli","category":"FOOD","latitude":40.7128,"longitude":-74.0060,"timestamp":"2026-06-17T16:02:00Z"}'
echo '      Expect: demo-geo-002  GEO_ANOMALY only  score 15  severity LOW  (6hrs → ~928 km/h < 1200 threshold)'

hr

# ── [4] Impossible Travel ─────────────────────────────────────────────────────

echo '[4/6] IMPOSSIBLE_TRAVEL — Cape Town → London in 30 min  (~19,340 km/h implied speed)'
publish 'demo-travel-001  Cape Town' \
  '{"transactionId":"demo-travel-001","userId":"user-travel","amount":200.00,"merchant":"CT Restaurant","category":"FOOD","latitude":-33.9249,"longitude":18.4241,"timestamp":"2026-06-17T10:05:00Z"}'
sleep 1
publish 'demo-travel-002  London  30 min later' \
  '{"transactionId":"demo-travel-002","userId":"user-travel","amount":300.00,"merchant":"London Hotel","category":"TRAVEL","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T10:35:00Z"}'
echo '      Expect: demo-travel-002  GEO_ANOMALY + IMPOSSIBLE_TRAVEL  score 75  severity HIGH'

hr

# ── [5] Velocity ──────────────────────────────────────────────────────────────

echo '[5/6] VELOCITY_CHECK — 11 transactions for the same user within 5 minutes'
for i in $(seq 1 11); do
  SEC=$(printf '%02d' "$i")
  TXN_ID=$(printf 'demo-vel-%03d' "$i")
  publish "$TXN_ID" \
    "{\"transactionId\":\"$TXN_ID\",\"userId\":\"user-vel\",\"amount\":10.00,\"merchant\":\"Online Store\",\"category\":\"RETAIL\",\"latitude\":51.5074,\"longitude\":-0.1278,\"timestamp\":\"2026-06-17T10:10:${SEC}Z\"}"
done
echo '      Expect: demo-vel-011 (11th)  VELOCITY_CHECK  score 40  severity MEDIUM'

hr

# ── [6] CRITICAL compound ─────────────────────────────────────────────────────

echo '[6/6] CRITICAL — High Amount + Geo Anomaly + Impossible Travel  (Cape Town → London in 30 min, $15,000)'
publish 'demo-crit-001  Cape Town  setup' \
  '{"transactionId":"demo-crit-001","userId":"user-crit","amount":100.00,"merchant":"CT Cafe","category":"FOOD","latitude":-33.9249,"longitude":18.4241,"timestamp":"2026-06-17T11:00:00Z"}'
sleep 1
publish 'demo-crit-002  London  30 min later  $15,000' \
  '{"transactionId":"demo-crit-002","userId":"user-crit","amount":15000.00,"merchant":"London Jewellers","category":"LUXURY","latitude":51.5074,"longitude":-0.1278,"timestamp":"2026-06-17T11:30:00Z"}'
echo '      Expect: demo-crit-002  HIGH_AMOUNT + GEO_ANOMALY + IMPOSSIBLE_TRAVEL  score 105  severity CRITICAL'

hr

# ── query helpers ─────────────────────────────────────────────────────────────

echo 'Done. Query the results:'
echo
echo "  # All flags"
echo "  curl '$API/api/v1/flags?size=50' | jq"
echo
echo "  # CRITICAL compound"
echo "  curl '$API/api/v1/flags/transactions/demo-crit-002/flags' | jq"
echo
echo "  # Impossible travel"
echo "  curl '$API/api/v1/flags/transactions/demo-travel-002/flags' | jq"
echo
echo "  # Stats across all demo data"
echo "  curl '$API/api/v1/flags/stats?from=2026-06-17T00:00:00Z&to=2026-06-17T23:59:59Z' | jq"
echo

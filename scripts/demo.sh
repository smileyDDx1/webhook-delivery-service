#!/usr/bin/env bash
# Walks through the main features against `docker compose up --build`.
# Needs only bash and curl. Run it from the repo root: ./scripts/demo.sh
set -euo pipefail

API="${API:-http://localhost:8080}"
ADMIN_KEY="${ADMIN_API_KEY:-local-admin-key-change-me}"
# the receiver's address as the app container sees it (inside the compose network)
RECEIVER="${RECEIVER:-http://demo-receiver:9090}"

# tiny JSON helper so the script doesn't need jq: prints the first value of a top-level field
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "1. Create an application (admin key). The API key is shown only once."
APP=$(curl -s -X POST "$API/api/v1/admin/applications" \
  -H "Authorization: Bearer $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"name": "demo-billing-service"}')
echo "$APP"
KEY=$(echo "$APP" | field apiKey)
auth=(-H "Authorization: Bearer $KEY" -H "Content-Type: application/json")

step "2. Register four endpoints on the demo receiver"
endpoint() {
  curl -s -X POST "$API/api/v1/endpoints" "${auth[@]}" \
    -d "{\"url\": \"$RECEIVER$1\", \"description\": \"$2\", \"eventTypes\": $3}" | field id
}
OK_ID=$(endpoint /ok "always answers 200" '[]')
FLAKY_ID=$(endpoint "/flaky?fail=0.5" "fails half the time" '["invoice.*"]')
DOWN_ID=$(endpoint /down "always 503" '[]')
GONE_ID=$(endpoint /gone "answers 410 Gone" '["customer.*"]')
echo "ok=$OK_ID flaky=$FLAKY_ID down=$DOWN_ID gone=$GONE_ID"

step "3. Publish 12 invoice.paid events, each with an Idempotency-Key"
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "event $i -> HTTP %{http_code}\n" -X POST "$API/api/v1/events" "${auth[@]}" \
    -H "Idempotency-Key: invoice-$i-paid" \
    -d "{\"eventType\": \"invoice.paid\", \"payload\": {\"invoiceId\": \"inv_$i\", \"amountCents\": $((i * 1000))}}"
done

step "4. Send invoice 1 again with the same key: no new event (200 + Idempotent-Replayed)"
curl -s -i -X POST "$API/api/v1/events" "${auth[@]}" -H "Idempotency-Key: invoice-1-paid" \
  -d '{"eventType": "invoice.paid", "payload": {"invoiceId": "inv_1", "amountCents": 1000}}' \
  | grep -iE "^HTTP|^idempotent-replayed"

step "5. A customer event goes to /ok, /down and /gone (not /flaky, it only wants invoice.*)"
curl -s -X POST "$API/api/v1/events" "${auth[@]}" \
  -d '{"eventType": "customer.deleted", "payload": {"customerId": "cus_9"}}'
echo

step "6. Waiting 30 seconds for deliveries and retries (5s, 20s...)"
sleep 30

step "7. Endpoint states: /down should be PAUSED by the circuit breaker, /gone DISABLED"
for id in "$OK_ID" "$FLAKY_ID" "$DOWN_ID" "$GONE_ID"; do
  # echo adds the newline: the JSON response has none, so sed's output wouldn't either
  { curl -s "$API/api/v1/endpoints/$id" "${auth[@]}"; echo; } \
    | sed -n 's/.*"url":"\([^"]*\)".*"state":"\([A-Z]*\)".*"consecutiveFailures":\([0-9]*\).*/\1  state=\2  failures in a row=\3/p'
done

step "8. Totals for the application"
curl -s "$API/api/v1/stats" "${auth[@]}"
echo

step "9. What the receiver saw"
curl -s "http://localhost:9090/stats"
echo

cat <<EOF

Try next:
  curl -s "$API/api/v1/deliveries?status=FAILED" -H "Authorization: Bearer $KEY"
  curl -s "$API/api/v1/endpoints/$FLAKY_ID/stats" -H "Authorization: Bearer $KEY"
  curl -s -X POST "$API/api/v1/endpoints/$GONE_ID/enable" -H "Authorization: Bearer $KEY"
  docker compose logs -f demo-receiver
EOF

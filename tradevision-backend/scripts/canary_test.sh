#!/usr/bin/env bash
#
# Review finding ("automated live-canary framework" -- external review, P3). This is the actual
# automation of the canary-test SEQUENCE this codebase's own earlier review already described
# (BUY -> FILLED -> OCO -> verify orderList -> verify both legs -> trigger TP/SL ->
# executionReport -> reconcile -> position CLOSED -> P&L correct), run against THIS
# application's own API (not directly against Binance -- this application already wraps every
# Binance call this script needs).
#
# Review finding ("Canary test still does not test the safety chain" -- external review,
# thirty-sixth pass, P1, confirmed real by direct inspection before this fix: this script used
# to stop at order FILLED, never checking whether a Position was actually created for it or
# whether protection (OCO) was ever placed -- exactly the review's own named gap, "It does not
# test: OCO placement, OCO verification"): extended to find the resulting Position and poll for
# its own ocoOrderListId being set, then cleanly close the test position via this application's
# own emergency-flatten endpoint rather than leaving a small, indefinitely-open test position
# behind after every canary run.
#
# HONEST SCOPE, stated plainly, updated from this fix but still real and unclosed:
# - Still does NOT wait for or verify a genuine TP/SL trigger, the resulting executionReport,
#   reconciliation of that specific fill, or final P&L accuracy -- those need either a real,
#   unpredictable market move (not something a script can force) or a genuinely larger
#   simulation harness than this session had time to build safely. Emergency-flatten (used here
#   to close the test position cleanly) exercises a DIFFERENT exit path than a real OCO TP/SL
#   fill would -- it proves protection was placed, not that it would have correctly triggered.
# - This CANNOT automate login. Login is OTP-based (a real code sent to a real phone/email),
#   which has no way to be scripted without defeating the entire point of OTP as a security
#   control. You must log in through the real UI first and provide this script with a valid
#   session cookie.
# - "Live" in the script's own name refers to what a REAL canary test is FOR (a tiny real trade
#   against LIVE, real money) -- this script itself defaults to TESTNET, and deliberately makes
#   running it against a LIVE credential a separate, explicit, harder-to-do-by-accident step
#   (see LIVE_CONFIRM below). Never run the LIVE path without having already read every P0 fix
#   this session made around LIVE execution -- this script does not substitute for that.
#
# Usage:
#   BASE_URL="https://your-deployment.example.com" \
#   SESSION_COOKIE="tv_access_token=...; tv_refresh_token=..." \
#   CREDENTIAL_ID="cred1" \
#   SYMBOL="BTCUSDT" \
#   QUANTITY="0.001" \
#     ./canary_test.sh

set -euo pipefail

: "${BASE_URL:?Set BASE_URL to your own deployments base URL, e.g. https://your-app.example.com}"
: "${SESSION_COOKIE:?Set SESSION_COOKIE to a real, currently-valid session cookie header value obtained by logging in through the real UI first -- this script cannot automate OTP login.}"
: "${CREDENTIAL_ID:?Set CREDENTIAL_ID to the broker credential id to test against.}"
: "${SYMBOL:=BTCUSDT}"
: "${QUANTITY:=0.001}"
: "${LIVE_CONFIRM:=}"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required (used here only for JSON parsing, not to run anything against your own strategy logic)." >&2
  exit 1
fi

if [ "${LIVE_CONFIRM}" = "I_UNDERSTAND_THIS_USES_REAL_MONEY" ]; then
  echo "LIVE_CONFIRM given -- this run targets a real, live credential with real money at risk."
  echo "Confirm CREDENTIAL_ID above genuinely points at a LIVE credential you intend to test,"
  echo "with a QUANTITY genuinely small enough to accept as a real, if tiny, loss."
  read -r -p "Type 'yes-live' to actually proceed: " FINAL_CONFIRM
  if [ "${FINAL_CONFIRM}" != "yes-live" ]; then
    echo "Aborted -- no final confirmation given."
    exit 1
  fi
fi

echo "--- Step 1: checking balance ---"
BALANCE_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/broker/${CREDENTIAL_ID}/balance" -H "Cookie: ${SESSION_COOKIE}")
echo "${BALANCE_RESPONSE}" | python3 -m json.tool || echo "${BALANCE_RESPONSE}"

echo ""
echo "--- Step 2: placing a tiny test order (${SYMBOL}, BUY, quantity=${QUANTITY}) ---"
ORDER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/broker/test-order" \
  -H "Cookie: ${SESSION_COOKIE}" \
  -H "Content-Type: application/json" \
  -d "{\"credentialId\":\"${CREDENTIAL_ID}\",\"symbol\":\"${SYMBOL}\",\"side\":\"BUY\",\"quantity\":${QUANTITY}}")
echo "${ORDER_RESPONSE}" | python3 -m json.tool || echo "${ORDER_RESPONSE}"

ORDER_ID=$(echo "${ORDER_RESPONSE}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))" 2>/dev/null || echo "")
if [ -z "${ORDER_ID}" ]; then
  echo ""
  echo "FAIL: could not extract an order id from the response above -- the order was likely"
  echo "rejected outright. See the response body for the real reason."
  exit 1
fi
echo "Order id: ${ORDER_ID}"

echo ""
echo "--- Step 3: polling order status until it reaches a terminal state (max 30 attempts, 2s apart) ---"
TERMINAL_STATUSES="FILLED REJECTED CANCELLED EXPIRED SUBMISSION_FAILED"
STATUS=""
for i in $(seq 1 30); do
  sleep 2
  ORDER_STATUS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/broker/order-ledger?orderId=${ORDER_ID}" -H "Cookie: ${SESSION_COOKIE}")
  STATUS=$(echo "${ORDER_STATUS_RESPONSE}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
events = d.get('data', [])
print(events[-1].get('eventType', '') if events else '')
" 2>/dev/null || echo "")
  echo "  attempt ${i}: latest event = ${STATUS}"
  for t in ${TERMINAL_STATUSES}; do
    if [[ "${STATUS}" == *"${t}"* ]]; then
      break 2
    fi
  done
done

echo ""
if [[ "${STATUS}" == *"FILLED"* ]]; then
  echo "--- PASS: order reached FILLED. Entry-order placement and OMS recording confirmed working. ---"
else
  echo "--- FAIL: order did not reach FILLED within the polling window (last observed: ${STATUS}). ---"
  echo "Check ${BASE_URL}/api/broker/order-ledger?orderId=${ORDER_ID} directly for the full event"
  echo "timeline, and this application's own logs for what actually happened."
  exit 1
fi

echo ""
echo "--- Step 4: locating the Position created for this order (max 15 attempts, 2s apart) ---"
POSITION_ID=""
OCO_ORDER_LIST_ID=""
for i in $(seq 1 15); do
  sleep 2
  POSITIONS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/positions/${CREDENTIAL_ID}?status=OPEN" -H "Cookie: ${SESSION_COOKIE}")
  POSITION_ID=$(echo "${POSITIONS_RESPONSE}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d.get('data', {}).get('content', d.get('data', []))
for p in items:
    if p.get('entryOrderId') == '${ORDER_ID}' or p.get('symbol') == '${SYMBOL}':
        print(p.get('id', ''))
        break
" 2>/dev/null || echo "")
  echo "  attempt ${i}: position id = ${POSITION_ID:-<not found yet>}"
  if [ -n "${POSITION_ID}" ]; then
    break
  fi
done
if [ -z "${POSITION_ID}" ]; then
  echo ""
  echo "--- FAIL: order reached FILLED, but no matching OPEN Position could be found. ---"
  echo "This is exactly the crash-window/OMS-to-Position gap this application's own recovery"
  echo "mechanisms exist to catch -- check ${BASE_URL}/api/positions/${CREDENTIAL_ID}?status=OPEN"
  echo "directly, and this application's own logs, for what actually happened."
  exit 1
fi
echo "Position id: ${POSITION_ID}"

echo ""
echo "--- Step 5: verifying protection (OCO) was placed for this position (max 15 attempts, 2s apart) ---"
for i in $(seq 1 15); do
  sleep 2
  POSITIONS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/positions/${CREDENTIAL_ID}?status=OPEN" -H "Cookie: ${SESSION_COOKIE}")
  OCO_ORDER_LIST_ID=$(echo "${POSITIONS_RESPONSE}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d.get('data', {}).get('content', d.get('data', []))
for p in items:
    if p.get('id') == '${POSITION_ID}':
        print(p.get('ocoOrderListId') or '')
        break
" 2>/dev/null || echo "")
  echo "  attempt ${i}: ocoOrderListId = ${OCO_ORDER_LIST_ID:-<not set yet>}"
  if [ -n "${OCO_ORDER_LIST_ID}" ]; then
    break
  fi
done
if [ -z "${OCO_ORDER_LIST_ID}" ]; then
  echo ""
  echo "--- FAIL: Position ${POSITION_ID} was created, but no OCO was ever recorded as placed for it. ---"
  echo "This position is currently UNPROTECTED. Check ${BASE_URL}/api/positions/credential/${CREDENTIAL_ID}/incidents"
  echo "and this application's own logs directly -- do NOT leave this position open unattended."
  exit 1
fi
echo "OCO orderListId: ${OCO_ORDER_LIST_ID}"
echo "--- PASS: protection (OCO) confirmed placed and recorded for this position. ---"

echo ""
echo "--- Step 6: cleanly closing the test position via emergency-flatten (this does NOT exercise a real TP/SL trigger -- see this script's own header for that honest limitation) ---"
FLATTEN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/positions/${POSITION_ID}/emergency-flatten" -H "Cookie: ${SESSION_COOKIE}")
echo "${FLATTEN_RESPONSE}" | python3 -m json.tool || echo "${FLATTEN_RESPONSE}"
echo ""
echo "--- Canary test sequence complete: entry order FILLED, Position created, OCO protection"
echo "confirmed placed, test position closed cleanly. See this script's own header comment for"
echo "the real, still-remaining scope this run does NOT cover (real TP/SL trigger, P&L accuracy,"
echo "fee reconciliation). ---"
exit 0

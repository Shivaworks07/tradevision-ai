# TradeVision AI — Disaster Recovery Runbook

Review finding ("No disaster-recovery runbook" — external review, twenty-third pass, P2).
Every procedure below names the actual mechanism already built into this codebase — not
generic advice. Where no mechanism exists yet, that is stated plainly rather than implied.

**Before touching anything in a real incident:** check `GET /actuator/health` first. It now
distinguishes `STARTING` / `RECONCILING` (`OUT_OF_SERVICE`) / `RECONCILIATION_FAILED` (`DOWN`)
/ a critical-index failure (`DOWN`) / normal worker health — this alone often tells you which
scenario below you're actually in.

---

## 1. MongoDB is unreachable or lost

**Detection:** `IndexInitializer.ensureCriticalIndexes()` fails at startup →
`StartupState.markCriticalIndexesResult(false)` → health reports `DOWN`. Mid-run: individual
operations start throwing, `AutoTradeService`'s own OMS-setup catch block fires (LIVE credentials
halt themselves automatically — see `OMS_SETUP_FAILED_LIVE_HALT` incidents).

**Immediate action:**
1. Do NOT restart the application yet if any LIVE credential still has open positions —
   restarting resets `lastProcessedCandleTime`'s in-memory half (the Mongo-backed
   `ScannedCandle` claim survives a restart, so a genuine duplicate-scan risk is already
   mitigated) but does NOT recover any position monitoring gap that occurred while Mongo was
   down. **A LIVE position with no reachable database has no local protection at all during
   the outage** — this application has no independent path to Binance for protection during
   this window.
2. Once Mongo is confirmed reachable again: restart the application. `PositionMonitorService`'s
   own startup reconciliation (`markReconciling()` → `markComplete(...)`) will re-derive
   position/order state directly from Binance, not from a Mongo snapshot that may itself be
   stale from before the outage.
3. Check every credential's own audit log (`BrokerCredentialService.audit`) for
   `OMS_SETUP_FAILED_LIVE_HALT` and `PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT` incidents
   raised during the outage window — each names the exact position/symbol affected and states
   plainly that manual verification against the exchange is required.
4. **Not built:** point-in-time recovery, backup restore drills, RPO/RTO targets (see P2-9 —
   this is an infrastructure/hosting decision, not application code, and is outside what this
   runbook's own codebase can resolve).

---

## 2. Application crashed (JVM died)

**Detection:** health endpoint unreachable; Kubernetes/host-level alerting (external to this
codebase) should be the actual trigger, not anything this application can self-report while dead.

**Immediate action:**
1. Restart. `StartupState` defaults to `STARTING` — nothing is falsely reported healthy during
   the restart window.
2. `PositionMonitorService`'s own startup reconciliation is the actual recovery mechanism here:
   it re-derives Order/Position state from Binance directly, not from assuming the last
   in-memory state was correct.
3. Check specifically for orders in `SUBMITTING` with a null `exchangeCallStartedAt` vs. a
   non-null one (see `Order.exchangeCallStartedAt`'s own field javadoc) —
   `recoverStuckSubmittingOrders` already distinguishes "never reached the exchange" from "may
   have reached the exchange," but this distinction is diagnostic, not automatically resolving:
   both cases still escalate to `RECONCILIATION_REQUIRED` for manual review if they can't be
   resolved cleanly.
4. If the crash happened between `markExecutionStarted()` and the real `placeOrder()` call (the
   documented, acknowledged exchange-boundary race — see P0-8's own kill-switch UI text): the
   post-halt/post-crash reconciliation pass is the only thing that will discover an order that
   reached the exchange with no local record whatsoever. There is no stronger guarantee than
   this without re-architecting around exchange-side idempotency further than this system
   already does with deterministic `clientOrderId`s.

---

## 3. Binance is unreachable (network partition, exchange outage)

**Detection:** `ExchangeHealthService`'s own error-rate/latency tracking; REST calls throwing;
`BinanceUserDataStreamService` reconnect-with-backoff attempts failing repeatedly.

**Immediate action:**
1. Do nothing destructive. This application's own design already treats a broker-call failure
   as "don't know," not "assume closed" or "assume still open" — `markUnknown`,
   `RECONCILIATION_REQUIRED` exist specifically for this.
2. Autonomous trading naturally stops producing NEW orders once REST calls start failing (the
   scan/order path itself will throw and be caught, non-fatally, per-symbol).
3. **Existing LIVE positions' own protective OCOs remain on Binance's own servers regardless of
   whether THIS application can reach them** — an OCO already placed does not depend on this
   application staying connected. The danger is only in NOT being able to react to a fill,
   cancellation, or a needed adjustment during the outage window.
4. Once connectivity returns: `BinanceUserDataStreamService`'s own reconnect logic re-establishes
   the WebSocket; `PositionMonitorService`'s scheduled reconciliation (and the immediate
   post-halt reconciliation added for the kill switch, if it was engaged) catches up.
5. If the outage is prolonged and you have LIVE positions you're not confident are still
   protected: manually verify directly on Binance's own UI/API. This application cannot tell
   you anything more than Binance itself can during a real Binance-side outage.

---

## 4. WebSocket connection lost (user-data stream)

**Detection:** `BinanceUserDataStreamService`'s own connection-state tracking;
`reconcileConnections()`'s scheduled eligibility sweep.

**Immediate action:**
1. This is the least severe of the connectivity scenarios: the WebSocket is a wake-up/fast-path
   mechanism (see the OMS fast-path's own javadoc) with REST reconciliation as the actual
   safety net, unconditionally, regardless of WebSocket state. A lost WebSocket alone does NOT
   mean this application loses track of positions — it means position/order state updates are
   slower (bounded by the REST reconciliation interval) until reconnection.
2. Reconnection is automatic (state-machine-driven, with backoff). No manual action is normally
   required.
3. If reconnection itself appears stuck (check connection state via logs/`ManagedConnection`):
   restart the specific credential's own connection is not currently exposed as an operator
   action — a full application restart is the fallback until such an endpoint exists.

---

## 5. An OCO's real state is unknown (exchange call outcome unclear)

**Detection:** `OrphanedOco` records; `ProtectionAttempt`s left in `SUBMITTING`;
`recoverStuckProtectionAttempts()`'s own scheduled sweep; `ORPHANED_OCO_PERSISTENCE_FAILED_LIVE_HALT`
incidents.

**Immediate action:**
1. `recoverStuckProtectionAttempts()` already asks Binance directly, by `origClientOrderId`, via
   `getOcoStatusByClientOrderId` — this is the actual, automated first line of recovery, and it
   already runs on a schedule. Check its own outcome in logs before doing anything manually.
2. If a `PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT` or
   `ORPHANED_OCO_PERSISTENCE_FAILED_LIVE_HALT` incident exists: the affected credential is
   already auto-halted (no NEW trades), and the incident message itself states plainly whether
   the OCO is confirmed-placed-but-unrecorded or genuinely unknown. Verify directly against
   Binance's own order-list-by-`origClientOrderId` API or UI for the specific position named in
   the incident.
3. Do NOT place a second, manual OCO for the same position without first confirming the first
   one does not exist — this risks two live protective order pairs on the same position.

---

## 6. A position's real state is unknown

**Detection:** `Position.status = RECONCILIATION_REQUIRED`; `NAKED_FLATTENED` (a flatten attempt
partially failed); `CLOSED_UNVERIFIED_PNL`.

**Immediate action:**
1. `BrokerCredentialService.delete()` already refuses to deactivate a credential with any
   position in these states — this is intentional, not a bug, so do not attempt to "clean up"
   by deactivating the credential to make the state disappear.
2. Query the position directly against Binance (`GET /api/v3/account` for current holdings,
   `GET /api/v3/allOrders` for the entry/exit order history) and compare against this
   application's own recorded `Position` document.
3. If the exchange shows the position genuinely closed but this application still shows it
   open (or vice versa): this is exactly what `CLOSED_UNVERIFIED_PNL` exists to flag — resolve
   the discrepancy manually, then correct the local record. There is currently no automated
   "trust the exchange, silently overwrite the local record" path, deliberately — silent
   auto-correction of a genuine discrepancy is exactly the kind of thing that should require a
   human's own judgment.

---

## 7. An API key is suspected compromised

**Detection:** unexpected orders/positions the application itself didn't originate; unexpected
balance changes; a user report.

**Immediate action, in this exact order:**
1. **`POST /api/broker/emergency-revoke-all`** — this is the actual, already-built emergency
   mechanism (`RiskProfileService.emergencyRevokeAll()`). It halts all trading, deactivates all
   credentials for the user, bumps the JWT token version (forcing re-authentication everywhere),
   and clears the refresh token. This is the single fastest in-application action available.
2. **Immediately after, on Binance's own side:** revoke the API key directly. This application
   has no programmatic way to do this — `emergencyRevokeAll()`'s own code explicitly documents
   this gap. Step 1 stops THIS application from using the key further; it does not stop the key
   itself from working if used directly against Binance by whoever compromised it.
3. Check Binance's own account activity log for anything that happened outside this
   application's own audit trail — a mismatch there is the actual evidence of compromise, not
   just a signal, and needs Binance's own support/security process, not anything this codebase
   can resolve.
4. **Not built:** AES key rotation with re-encryption of historical credentials still at rest.
   If the compromise is suspected to include the server's own database (not just the exchange
   key), this application's own stored, encrypted credentials for OTHER users may also warrant
   review — this is a broader incident than a single user's key.

---

## 8. A duplicate order is suspected

**Detection:** two orders with the same intent (same signal, same symbol, same direction) close
together; a user report of "it traded twice."

**Immediate action:**
1. Check `Order.clientOrderId` for both suspected orders — this application enforces a unique
   index on this field (see `IndexInitializer`), and Binance itself treats `clientOrderId` as
   an idempotency key. If both orders have genuinely different `clientOrderId`s, they were
   genuinely two separate, deliberate submissions (not a retry of the same one) — look at
   `TradeCallRecord`/`signalId` to see if two different signals both fired for a
   correlated reason (e.g., the correlation-group exposure check, or two different plans
   scanning the same symbol).
2. If investigating a suspected reservation-related duplicate specifically: this session's own
   P0-1 fix closed the confirmed gap where a failed execution could release a plan-level slot
   it never actually held. If a duplicate is found from BEFORE that fix was deployed, it is a
   known, now-closed class of bug — check the deployment timestamp against when this fix
   shipped.
3. If the duplicate genuinely traces back to the SAME signal being evaluated twice: check
   `ScannedCandle`'s own claim records (this session's own P1-6 fix) for that exact
   credential/symbol/timeframe/candle-close-time combination — a genuine duplicate claim
   succeeding would itself be evidence of an index or claim-logic failure, not expected
   behavior.

---

## What this runbook does not cover

Backup/restore procedures, RPO/RTO targets, and scheduled restore drills (P2-9) are
infrastructure/hosting decisions outside this application's own code — they need to be defined
and owned wherever this application is actually deployed, not invented here without knowing the
real hosting environment. Device-fingerprinting and credential-stuffing alerting (part of P2-8)
are not built into this codebase and are not assumed above.

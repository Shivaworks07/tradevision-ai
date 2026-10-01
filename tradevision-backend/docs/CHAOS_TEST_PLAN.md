# TradeVision AI — Chaos Test Plan

Review finding ("formal chaos-test suite" — external review, P3). This document names the real
scenarios worth deliberately injecting failure into, grounded in this session's own actual fixes
— not generic chaos-engineering advice. `ScannedCandleClaimIntegrationTest.java` and
`ChaosMongoUnavailableIntegrationTest.java` (both in `src/test/java/com/tradevision/integration/`)
are real, written tests for two of the scenarios below — **honestly disclosed as unexecuted**:
`docker ps` fails outright in this sandbox ("docker: not found"), so neither has actually been run
here. Run them on a machine with Docker available before trusting them.

## Why this list, not a generic one

Every scenario below traces to a specific, real fix made either in this session or an earlier one
— the point of a chaos test is to prove a *specific claimed guarantee* actually holds under real
failure, not to inject failure for its own sake.

## Scenario 1 — MongoDB becomes unavailable mid-execution-claim

**What it proves:** `AutoTradeService`'s own OMS-setup catch block (P0-2, this session) genuinely
halts LIVE trading rather than proceeding to call Binance with no durable local record.

**How to inject it:** Using Testcontainers' own `MongoDBContainer`, start a real MongoDB, let the
application connect, then call `container.stop()` (or `container.pause()` for a more realistic
"network partition" rather than a clean stop) at the exact moment `orderService.create(...)` would
be called — achievable by injecting a test double around that one call that blocks until the
container is confirmed down.

**Expected outcome:** for a LIVE credential, `adapter.placeOrder()` is never invoked. For
TESTNET/PAPER, the existing lenient behavior continues (by design — see this session's own
`OMS_SETUP_FAILED_LIVE_HALT` fix).

## Scenario 2 — The JVM is killed between `markExecutionStarted()` and the real exchange call

**What it proves:** this is the review's own repeatedly-named, explicitly *unclosable* gap
(P0-8, this session) — a kill switch or process death here cannot be physically prevented, only
detected and reconciled after the fact.

**How to inject it:** requires a real, separate process (not a JUnit test in the same JVM) —
start the application, trigger a signal evaluation, and `kill -9` the process at a randomized
point during the authorization-to-execution window (a small `Thread.sleep` injected via a test
profile is the only realistic way to reliably land inside this narrow window).

**Expected outcome:** on restart, `PositionMonitorService`'s own startup reconciliation discovers
the order's real state directly from Binance (not from a Mongo snapshot that predates the crash).
This is the one scenario in this plan that this session could not write even an unexecuted test
for — it needs process-level control (`kill -9`, process restart, then inspection) that a JVM
test harness cannot express; it needs a real, separate orchestration script.

## Scenario 3 — MongoDB loses transaction support mid-flight (replica set → standalone)

**What it proves:** `authorizeLiveAutoTrade`'s own startup-time check (P1-4, this session)
refuses LIVE authorization on a deployment that doesn't support transactions — this scenario
proves the check is genuinely evaluated at the right time, not cached stale.

**How to inject it:** start a real MongoDB replica set via Testcontainers, confirm
`IndexInitializer.checkMongoTransactionSupport()` reports `true`, restart the application against
a standalone (non-replica-set) MongoDB instance instead, and confirm it now reports `false` and
LIVE authorization is refused.

## Scenario 4 — Two application instances race to claim the same candle

**What it proves:** `ScannedCandle`'s own unique-index claim (P1-6, this session) genuinely
enforces exactly-one-winner under real concurrent load against a real database, not just a mock.

**Already written:** `ScannedCandleClaimIntegrationTest.java` (this session) — 20 concurrent
threads racing for the identical claim key, against a real MongoDB unique index.

## Scenario 5 — Binance responds with a genuinely ambiguous result (timeout mid-OCO-placement)

**What it proves:** `OcoOrderResult.verificationUncertain` (P1-7, this session) is correctly
distinguished from a confirmed failure, and the caller correctly refuses to emergency-flatten
against an unverified state.

**How to inject it:** requires a fake/mock HTTP layer that accepts the OCO placement request but
never returns a response (simulating a genuine network timeout) — Testcontainers' own Toxiproxy
integration (a dedicated chaos-proxy container) is the right tool for this specific scenario,
not plain Mockito, since the point is testing real timeout/retry behavior, not a scripted mock
response. Not written as an actual test file in this pass — Toxiproxy setup is a real, separate
piece of infrastructure this session did not have time to also configure and verify.

## What this plan does not cover

Resource exhaustion (OOM, disk full), and multi-region/availability-zone failures — genuinely
real chaos-engineering concerns, but this application's own current single-instance,
single-region deployment (see P2-5's own disclosure) makes them premature to test before the
deployment itself is ready to be more than one instance in more than one place.

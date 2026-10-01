# ADR-0002: MongoDB-backed atomic counters instead of Redis

## Status
Accepted (already implemented; this ADR documents an existing, established pattern).

## Context
This codebase needs several cross-replica-safe counters: a per-IP proxy rate limit
(`DistributedRateLimitService`), exposure reservations (`ExposureReservationService`), position
slot reservations, and a dashboard-only exchange-health rolling window
(`ExchangeHealthService`). Each needs the SAME underlying guarantee — an atomic
"read current value, only proceed if it's still under some cap, then increment" — shared correctly
across every application replica, not just within one JVM's own memory.

The obvious alternative for this kind of counter is Redis (`INCR`, Lua scripts for
compare-and-swap, or a token-bucket library). This codebase does not use Redis anywhere, and none
of these counters are on a path latency-sensitive enough to justify adding a new piece of
infrastructure, an new operational dependency, and a new failure mode purely for this.

## Decision
Use MongoDB's own atomic `findAndModify` / `updateFirst` with a conditional query (e.g.
`.and("count").lte(cap - amount)`) as the compare-and-swap primitive, and a fixed time-window
document (reset via a conditional upsert when the window has expired) rather than a true sliding
window. MongoDB is already a required, already-operated dependency of this application — every one
of these counters gets genuine cross-replica atomicity for zero new infrastructure.

**Known, disclosed trade-off**: this is a fixed window, not sliding-window precision — a caller
right at a window boundary can get up to roughly double the intended rate over a short span
straddling the reset. This is judged acceptable for every current use (a rate limit, a dashboard
health signal, a reservation cap that already has other independent safety margins) — it would NOT
be an acceptable trade-off for something like an exchange-facing order-rate limiter with a hard
regulatory or exchange-enforced ceiling, which would need true sliding-window or token-bucket
precision instead.

## Consequences
- A new atomic counter should default to this same pattern (conditional `findAndModify`/
  `updateFirst` against a MongoDB document) rather than reaching for Redis or an in-JVM structure,
  unless it specifically needs sliding-window precision or a latency budget MongoDB genuinely
  can't meet — in which case that specific exception should be its own ADR, not a silent
  one-off deviation.
- A per-call, synchronous Mongo round trip is the accepted cost for a counter directly gating a
  trading decision (exposure, position slots, order rate limits). For a counter that's PURELY a
  dashboard/observability signal and gates no decision at all (see `ExchangeHealthService.record`,
  P3-7), prefer an in-memory delta with a periodic flush instead — the atomicity this ADR provides
  isn't needed there.

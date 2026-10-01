# ADR-0001: Fail-open vs. fail-closed, by trading mode

## Status
Accepted (already implemented across the codebase; this ADR documents an existing, established
pattern rather than proposing a new one).

## Context
Several bookkeeping/dedup/reservation mechanisms in this codebase (candle-processing claims in
`AutonomousScannerService.tryClaimCandleProcessing`, exposure reservations in
`ExposureReservationService.reserve`, distributed rate limiting in `DistributedRateLimitService`)
can fail for reasons unrelated to the thing they're actually deciding — a transient MongoDB
connection issue, a timeout, a session-acquisition failure. Each one independently had to decide:
if the mechanism itself can't tell whether "already claimed" is true or false, should the caller be
allowed to proceed anyway, or should it be blocked?

Before this ADR existed, that reasoning was repeated near-verbatim in the javadoc of every one of
these methods across several files.

## Decision
The answer depends on what mode the credential is trading in, not on the mechanism:

- **LIVE**: fail CLOSED on any ambiguous result. A missed signal or a rejected reservation is
  strictly safer than an unknown duplicate evaluation or an unverified exposure claim when real
  money is on the line — duplicate risk extends beyond a duplicate exchange order (duplicate risk
  reservations, duplicate OMS attempts, duplicate state transitions all remain possible even with
  deterministic `clientOrderId` protection at the exchange boundary).
- **TESTNET/PAPER**: fail OPEN. No real money is at risk, so availability wins — a transient
  infrastructure hiccup should degrade to "proceed anyway, worst case a harmless duplicate scan or
  reservation," not stop simulated trading from working at all.

A `BrokerMode`/`live` parameter is threaded through to whichever call site needs to make this
decision, rather than each mechanism guessing from context.

## Consequences
- Any NEW mechanism with the same "can't verify, must decide" shape should follow this same
  policy rather than inventing its own — LIVE fails closed, TESTNET/PAPER fails open.
- A method implementing this should link here instead of re-deriving the reasoning inline; keep
  only what's specific to that one call site (e.g. the exact exception types this codebase can
  actually observe from MongoDB at this point) in the local comment.

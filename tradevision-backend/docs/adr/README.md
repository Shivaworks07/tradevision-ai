# Architecture Decision Records (ADRs)

## Why this exists

P3-8 fix ("Code hygiene -- ~50% of source lines are review-narrative comments; fully-qualified
class names inline -- external review -- Move rationale to ADRs; normal imports" -- confirmed
real: a line-count check across `src/main/java` found several files with 65-80%+ of their lines
being comments, almost all of it "Review finding (...)" narrative explaining *why* a fix was made,
not *what* the code does). That narrative is genuinely valuable — this codebase's whole audit
history lives in it — but inline in the source it makes files hard to scan, repeats the same
rationale across many call sites, and gets stale the moment a related decision changes elsewhere
without every comment being found and updated too.

This directory is where that kind of rationale belongs going forward: a decision that explains
*why* the codebase works a certain way, referenced from the code with a short pointer, instead of
paragraphs of narrative repeated inline.

## HONEST SCOPE — read this before assuming everything already moved

This pass does **not** rewrite the whole codebase. `src/main/java` is ~36,000 lines with this
narrative style established consistently across the whole review history captured in
`TradeVision_Production_Audit.md`; mechanically moving all of it in one pass, across every file,
with no test able to verify a pure documentation-shuffle didn't drop or garble any of it, is a
disproportionate amount of risk for a P3 "nice to have." What this pass actually did:

1. Established this directory and convention.
2. Wrote ADR-0001 through ADR-0003 below, capturing the three rationale threads that were most
   frequently repeated near-verbatim across many different files' inline comments (fail-open vs.
   fail-closed policy, the MongoDB-backed atomic-counter pattern, and this session's own comment
   style itself) — these were the best return on a bounded effort, since each one previously had
   its reasoning duplicated in five or more separate files.
3. Trimmed the inline comments in a few representative files (`RiskProfile.java`, `BrokerMode.java`
   — see their own updated headers) down to a one-line pointer at the relevant ADR, as a concrete
   example of the pattern for future files.
4. Normalized a few of the most visible fully-qualified inline class name usages (see
   `ShutdownState.java`, `ExchangeHealthService.java` from this same review pass) to real imports.

Everything else keeps its existing inline "Review finding (...)" comments unchanged. Migrating the
rest is real, further, incremental work — do it file-by-file as those files are next touched for
an unrelated reason, rather than as a single, large, high-risk mechanical pass.

## Index

- [ADR-0001: Fail-open vs. fail-closed by trading mode](0001-fail-open-vs-fail-closed-by-mode.md)
- [ADR-0002: MongoDB-backed atomic counters instead of Redis](0002-mongodb-atomic-counters.md)
- [ADR-0003: Inline review-narrative comments, and when to promote one to an ADR](0003-review-comment-convention.md)

# ADR-0003: Inline review-narrative comments, and when to promote one to an ADR

## Status
Accepted.

## Context
This codebase's history is an extensive, multi-pass external production audit
(`TradeVision_Production_Audit.md`), and nearly every non-trivial fix in it carries an inline
comment explaining what the review found, why the fix is correct, and what it deliberately does
NOT cover ("HONEST SCOPE" disclosures). That narrative has real, ongoing value — it's the reason a
future change doesn't accidentally re-introduce a bug that was already found and fixed once, and
it's what makes this codebase's own "disclosed scope-narrowing" idiom possible (saying plainly
what a fix does not cover, instead of silently leaving a gap or overclaiming completeness).

The P3-8 review finding is not that this narrative shouldn't exist — it's that ~50%+ of some
files' lines being comments, much of it repeating the SAME reasoning at multiple call sites, makes
those files hard to scan and creates several places that all need updating if the underlying
reasoning ever changes.

## Decision
Two different kinds of comment, two different homes:

1. **Local, call-site-specific reasoning** — why THIS particular field, THIS particular exception
   type, or THIS particular edge case needed handling here — stays inline, close to the code it
   explains. This is the majority of existing comments and is NOT what this ADR asks anyone to
   move.
2. **A reusable decision that applies across multiple files/call sites** — a policy, a chosen
   pattern, a named trade-off that gets re-derived or re-explained in more than a couple of
   places — belongs in an ADR under `docs/adr/`, referenced from each call site with a short
   pointer ("see ADR-000N") instead of the full reasoning repeated inline.

A comment is a good candidate for promotion to an ADR when: it starts with the same reasoning as a
comment already written somewhere else in the codebase, or it's explaining a general policy
("LIVE fails closed, TESTNET/PAPER fails open") rather than something specific to the one line
it's attached to.

Fully-qualified inline class names (`com.tradevision.foo.Bar` written out in the middle of a method
body instead of a normal `import`) are a separate, smaller finding under the same P3-8 item —
these exist in a handful of files from incremental edits that didn't add an import at the top.
Prefer a normal import; only use a fully-qualified name inline for a genuine, one-off disambiguation
between two same-named classes in the same file.

## Consequences
- New fixes should keep writing local reasoning inline, as this codebase always has.
- Before writing a paragraph of policy-level reasoning inline, check `docs/adr/` first — it may
  already be written, and the fix only needs a pointer plus whatever is genuinely specific to this
  one call site.
- This convention does not retroactively rewrite existing files; see `docs/adr/README.md`'s own
  "HONEST SCOPE" section for exactly what this pass did and did not touch.

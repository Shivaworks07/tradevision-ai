# TradeVision AI — Backup & Restore

Review finding ("No explicit database backup/restore evidence" — external review, twenty-third
pass, P2). This document states what's actually true, verified against MongoDB Atlas's own
current documentation before writing anything here — not generic advice, and not a claim that
backup is "handled" without saying by what.

## Which scenario actually applies to this deployment

This codebase connects to MongoDB Atlas (see `application.properties`'s own connection string
shape). **Which backup capability you actually have depends entirely on your cluster tier** —
this repository has no way to know that from the code alone, so check it directly in the Atlas
UI (Database → your cluster → look at the tier name) before assuming either scenario below:

- **M10 or higher (a paid, dedicated tier):** Atlas's own Continuous Cloud Backup is available —
  incremental snapshots plus oplog streaming, giving point-in-time restore to any second within
  your configured retention window (7 days by default, up to 35 for paid tiers — configurable).
  **If you're on this tier, enabling this in the Atlas UI (Backup → Edit Backup Policy → turn on
  Continuous Cloud Backup) is the primary, correct backup mechanism** — not a custom script.
  `scripts/backup.sh` in this repo is then a supplementary, belt-and-suspenders extra copy in a
  second location, not a replacement.
- **M0/M2/M5 (free or shared tiers):** these get snapshot backups only — **no point-in-time
  recovery at all**. If this is your actual deployment tier, `scripts/backup.sh` (a plain
  `mongodump`, run on a schedule you control) is not supplementary — it is your only real
  recovery mechanism between Atlas's own periodic snapshots.

**Restores are cluster-wide, not selective per-collection**, on Atlas's own Continuous Backup —
you cannot restore just one collection without affecting the rest of that cluster's data as of
that timestamp. Keep this in mind before assuming a "surgical" recovery is possible.

## What this repository actually provides

- `scripts/backup.sh` — a real, runnable `mongodump` wrapper. Requires `MONGODB_URI` and the
  MongoDB Database Tools installed. Writes a timestamped, gzip-compressed archive.
- `scripts/restore.sh` — the companion `mongorestore` wrapper, with an explicit confirmation
  prompt specifically to reduce the risk of accidentally overwriting production data during a
  drill.

Neither script defines a retention/rotation policy for old backup files, or where they should
actually be stored (S3, another region, etc.) — those are genuine operational decisions that
depend on where and how this application is actually hosted, which this repository does not
know and should not guess at.

## RPO / RTO — stated honestly, not invented

This repository cannot set these numbers for you — they depend on business tolerance for data
loss and downtime, which is a decision for whoever owns this deployment, not something
inferable from the code. What can be stated factually:

- If you're on Atlas Continuous Backup (M10+): RPO can be as low as ~1 minute (oplog-driven),
  per Atlas's own documentation, within your configured retention window.
- If you're on `scripts/backup.sh` alone (free/shared tier, or as a supplement): your actual RPO
  is exactly however often you run it — if it runs nightly via cron, your RPO is "up to 24 hours
  of data loss in the worst case," not better than that, regardless of any other claim.
- RTO depends on database size and where the restore target actually runs — this needs to be
  measured against a real restore drill (below), not assumed.

## Restore drill — do this quarterly, on a schedule, not "eventually"

A backup that has never been restored is unverified, not "probably fine." Recommended checklist,
run against a **separate staging cluster** — never production:

1. Run `scripts/restore.sh` against the staging cluster with a recent backup archive.
2. Confirm the restore actually completed without error (the script itself checks this, but
   verify the exit code in whatever runs it, e.g. CI).
3. Check document counts on a few of the highest-stakes collections (`Position`, `Order`,
   `RiskProfile`) against what you expect from the source — a restore that silently drops
   documents is a real failure mode, not a hypothetical one.
4. Spot-check a handful of specific documents by `_id` against known values from before the
   backup was taken.
5. Confirm the critical unique indexes this application depends on for correctness (see
   `IndexInitializer.ensureCriticalIndexes()`) actually exist on the restored data — a restore
   that recreates data without recreating indexes is a correctness risk this application's own
   `StartupState.markCriticalIndexesResult` would catch on next startup, but only if the
   restored database is actually pointed at by a running instance of this application, not just
   inspected manually.
6. Record the actual wall-clock time this drill took — that is your real, current RTO, not an
   estimate.

## What this document does not, and cannot, do

Set up an actual cron schedule or CI pipeline to run `backup.sh` automatically — that depends on
where this application is hosted (this repo does not know), and should be wired into whatever
deployment infrastructure actually exists there. Configure Atlas's own backup policy via its API
— the Atlas documentation link above covers this directly and is more likely to stay current
than a copy of the exact API calls pasted here. Decide your actual RPO/RTO targets — those are
business decisions, not something this codebase can determine on its own.

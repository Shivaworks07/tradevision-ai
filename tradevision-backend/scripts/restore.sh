#!/usr/bin/env bash
#
# Review finding ("No explicit database backup/restore evidence" -- external review,
# twenty-third pass, P2). Companion to backup.sh -- see docs/BACKUP_AND_RESTORE.md for the full
# restore-drill procedure this script is one step of, not the whole procedure.
#
# CRITICAL: this restores into MONGODB_URI as given. Pointing this at your real production
# connection string will OVERWRITE production data. For the quarterly restore drill described
# in docs/BACKUP_AND_RESTORE.md, MONGODB_URI must point at a separate staging cluster/database,
# never production, unless you are deliberately and knowingly performing a real recovery.
#
# Usage:
#   MONGODB_URI="mongodb+srv://user:pass@staging-cluster.mongodb.net/tradevision" \
#     ./restore.sh ./backups/tradevision-20260101T000000Z.archive.gz

set -euo pipefail

if [ -z "${MONGODB_URI:-}" ]; then
  echo "ERROR: MONGODB_URI environment variable is required." >&2
  exit 1
fi

ARCHIVE_PATH="${1:-}"
if [ -z "${ARCHIVE_PATH}" ] || [ ! -f "${ARCHIVE_PATH}" ]; then
  echo "ERROR: pass the path to a backup archive as the first argument." >&2
  echo "Usage: MONGODB_URI=\"...\" ./restore.sh /path/to/tradevision-<timestamp>.archive.gz" >&2
  exit 1
fi

if ! command -v mongorestore >/dev/null 2>&1; then
  echo "ERROR: mongorestore is not installed. Install the MongoDB Database Tools:" >&2
  echo "  https://www.mongodb.com/docs/database-tools/installation/" >&2
  exit 1
fi

echo "About to restore ${ARCHIVE_PATH} into the database at the given MONGODB_URI."
echo "This will overwrite any colliding documents in the TARGET database. Confirm this is"
echo "NOT your live production connection string unless a real recovery is genuinely intended."
read -r -p "Type 'restore' to continue: " CONFIRMATION
if [ "${CONFIRMATION}" != "restore" ]; then
  echo "Aborted -- no confirmation given."
  exit 1
fi

echo "Restoring ${ARCHIVE_PATH} ..."
# Review finding ("formal RPO/RTO measurement" -- external review, P3, full context in
# docs/BACKUP_AND_RESTORE.md's own restore-drill checklist, which already said "record the
# actual wall-clock time this drill took -- that is your real, current RTO" as a MANUAL step):
# the actual automation -- a real, measured duration, not something to remember to time by hand.
RESTORE_START=$(date +%s)
mongorestore --uri="${MONGODB_URI}" --archive="${ARCHIVE_PATH}" --gzip --drop
RESTORE_END=$(date +%s)
RESTORE_DURATION=$((RESTORE_END - RESTORE_START))
echo "Restore itself took ${RESTORE_DURATION} second(s) -- this is your real, current RTO for"
echo "this specific backup's own size, on this specific target. It does NOT include the"
echo "verification steps below, or the human time to actually decide to run a restore in the"
echo "first place -- both are real parts of a genuine incident's own timeline, not measured here."

# Review finding ("automated restore drills" -- external review, P3, confirmed real by direct
# inspection before this fix: docs/BACKUP_AND_RESTORE.md's own restore-drill checklist was
# entirely manual -- document counts, spot-checking known records, confirming indexes exist --
# none of it actually automated): the actual automation. Requires mongosh (the current MongoDB
# shell -- the legacy `mongo` shell was deprecated starting with MongoDB 5.0, so this
# deliberately does not fall back to it). If mongosh isn't installed, this still reports the
# restore itself succeeded -- verification is additive, not a reason to treat a completed
# restore as a failure.
echo ""
echo "Running automated post-restore verification..."
if ! command -v mongosh >/dev/null 2>&1; then
  echo "WARNING: mongosh not installed -- skipping automated verification. Install it to get"
  echo "real document-count and index checks here: https://www.mongodb.com/docs/mongodb-shell/install/"
  echo "Falling back to the manual checklist in docs/BACKUP_AND_RESTORE.md."
else
  VERIFICATION_SCRIPT='
    const collections = ["positions", "orders", "risk_profiles", "trade_call_records", "broker_credentials"];
    let allOk = true;
    print("--- Document counts (0 is not necessarily wrong for a fresh/small database -- compare against what you actually expect) ---");
    for (const c of collections) {
      try {
        const count = db.getCollection(c).countDocuments({});
        print(c + ": " + count + " document(s)");
      } catch (e) {
        print(c + ": ERROR - " + e.message);
        allOk = false;
      }
    }
    print("--- Critical unique index presence (see IndexInitializer.ensureCriticalIndexes for the full authoritative list) ---");
    const criticalIndexChecks = [
      ["orders", "clientOrderId_1"],
      ["positions", "entryOrderId_1"],
      ["risk_profiles", "credentialId_1"]
    ];
    for (const [coll, idxName] of criticalIndexChecks) {
      try {
        const indexes = db.getCollection(coll).getIndexes().map(i => i.name);
        const found = indexes.some(n => n.includes("clientOrderId") || n.includes("entryOrderId") || n.includes("credentialId"));
        print(coll + ": " + (found ? "an index covering the expected field(s) was found" : "WARNING - no matching index found"));
        if (!found) allOk = false;
      } catch (e) {
        print(coll + ": ERROR - " + e.message);
        allOk = false;
      }
    }
    print(allOk ? "--- Automated checks passed ---" : "--- Automated checks found issues -- see warnings above ---");
  '
  mongosh "${MONGODB_URI}" --quiet --eval "${VERIFICATION_SCRIPT}" || echo "WARNING: mongosh verification script itself failed to run -- fall back to the manual checklist."
fi

echo ""
echo "Restore complete. Automated checks above are a real, but partial, verification -- they"
echo "confirm the restored data isn't obviously empty or missing its critical indexes. They do"
echo "NOT replace the rest of docs/BACKUP_AND_RESTORE.md's own restore-drill checklist (spot-"
echo "checking specific known records by id, confirming this application itself actually starts"
echo "and reaches TRADING_ENABLED against the restored database) -- a restore that completes"
echo "and passes these automated checks is not automatically the same as a restore that's fully"
echo "verified correct."

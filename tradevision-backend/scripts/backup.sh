#!/usr/bin/env bash
#
# Review finding ("No explicit database backup/restore evidence" -- external review,
# twenty-third pass, P2, full context in docs/BACKUP_AND_RESTORE.md). This script is a
# supplementary, tier-independent backup mechanism -- it works regardless of which MongoDB
# Atlas cluster tier this application is deployed against. See docs/BACKUP_AND_RESTORE.md for
# the full context on when this is your PRIMARY backup mechanism (free/shared Atlas tiers,
# which have no point-in-time recovery at all) versus a SUPPLEMENTARY one (M10+ tiers, which
# already have Atlas's own continuous backup and PITR -- this script is then a belt-and-suspenders
# extra copy in a second location, not a replacement for that).
#
# Usage:
#   MONGODB_URI="mongodb+srv://user:pass@cluster.mongodb.net/tradevision" ./backup.sh
#
# Writes a timestamped, compressed archive to ./backups/. Does NOT delete old backups itself --
# that retention decision belongs in docs/BACKUP_AND_RESTORE.md's own stated policy, enforced by
# whatever schedules this script (cron, a CI pipeline, etc.), not hardcoded here.

set -euo pipefail

if [ -z "${MONGODB_URI:-}" ]; then
  echo "ERROR: MONGODB_URI environment variable is required." >&2
  echo "Usage: MONGODB_URI=\"mongodb+srv://...\" ./backup.sh" >&2
  exit 1
fi

if ! command -v mongodump >/dev/null 2>&1; then
  echo "ERROR: mongodump is not installed. Install the MongoDB Database Tools:" >&2
  echo "  https://www.mongodb.com/docs/database-tools/installation/" >&2
  exit 1
fi

BACKUP_DIR="./backups"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE_PATH="${BACKUP_DIR}/tradevision-${TIMESTAMP}.archive.gz"

mkdir -p "${BACKUP_DIR}"

echo "Starting backup to ${ARCHIVE_PATH} ..."
mongodump --uri="${MONGODB_URI}" --archive="${ARCHIVE_PATH}" --gzip

if [ ! -s "${ARCHIVE_PATH}" ]; then
  echo "ERROR: backup archive was not created or is empty -- treat this as a failed backup, not a silent success." >&2
  exit 1
fi

ARCHIVE_SIZE=$(du -h "${ARCHIVE_PATH}" | cut -f1)
echo "Backup complete: ${ARCHIVE_PATH} (${ARCHIVE_SIZE})"
echo "Restore with: mongorestore --uri=\"\$MONGODB_URI\" --archive=\"${ARCHIVE_PATH}\" --gzip"
echo ""
echo "IMPORTANT: this backup is only as good as your last successful RESTORE TEST. See"
echo "docs/BACKUP_AND_RESTORE.md for the quarterly restore-drill procedure this script alone"
echo "does not verify -- a backup file existing is not the same as confirming it actually restores."

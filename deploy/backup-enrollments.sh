#!/usr/bin/env bash
# Snapshot the per-viewer enrollments + audit log (the push_data volume) so a VPS
# loss doesn't wipe who-can-see-the-camera. Cheap insurance; cron-friendly.
#
#   ./backup-enrollments.sh [output-dir]    # default: ~/portal-backups
#
# Cron (daily 03:00, keep last 30):
#   0 3 * * * /opt/portal/deploy/backup-enrollments.sh >> /var/log/portal-backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")"

OUT="${1:-$HOME/portal-backups}"
mkdir -p "$OUT"
STAMP="$(date +%Y%m%d-%H%M%S)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Pull /data (viewers.json + audit.json) out of the running signaling container.
docker compose cp signaling:/data/. "$TMP/"
tar czf "$OUT/enrollments-$STAMP.tar.gz" -C "$TMP" .

# Retain the 30 most recent snapshots.
ls -1t "$OUT"/enrollments-*.tar.gz 2>/dev/null | tail -n +31 | xargs -r rm -f

echo "Backed up enrollments to $OUT/enrollments-$STAMP.tar.gz"

#!/bin/sh
set -eu
: "${BACKUP_INTERVAL_SECONDS:=86400}"
while true; do
  sh /scripts/backup.sh "/backups/quant-$(date -u +%Y%m%dT%H%M%SZ)"
  date -u +%s > /backups/last-success.timestamp
  sleep "$BACKUP_INTERVAL_SECONDS"
done

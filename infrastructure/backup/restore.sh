#!/bin/sh
# Restore only to a NEW database. Provision application roles first; never reset the source.
set -eu
: "${PGHOST:?PGHOST required}" "${PGUSER:?PGUSER required}"
dump=${1:?Usage: restore.sh /backups/name.dump new_database}
target=${2:?New database name required}
case "$target" in ''|*[!a-zA-Z0-9_]*) echo 'Use an alphanumeric database name' >&2; exit 1;; esac
(cd "$(dirname "$dump")" && sha256sum -c "$(basename "$dump").sha256")
createdb "$target"
psql -X -v ON_ERROR_STOP=1 -d "$target" -c 'CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE' -c 'SELECT timescaledb_pre_restore()'
# Keep original ownership and grants; roles must already exist. No --clean or parallel restore.
if pg_restore --exit-on-error --dbname="$target" "$dump"; then
  psql -X -v ON_ERROR_STOP=1 -d "$target" -c 'SELECT timescaledb_post_restore()' -c 'ANALYZE'
else
  psql -X -v ON_ERROR_STOP=1 -d "$target" -c 'SELECT timescaledb_post_restore()'
  echo 'Restore failed; quarantine the new database for investigation' >&2
  exit 1
fi
echo "Restored to new database: $target"

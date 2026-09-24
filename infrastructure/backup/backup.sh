#!/bin/sh
# Run with a PostgreSQL 18 client matching the server. Credentials come from PGPASSFILE.
set -eu
umask 077
: "${PGHOST:?PGHOST required}" "${PGUSER:?PGUSER required}" "${PGDATABASE:?PGDATABASE required}"
prefix=${1:?Usage: backup.sh /durable-backups/unique-prefix}
[ ! -e "$prefix.dump" ] && [ ! -e "$prefix.dump.partial" ] || { echo 'Backup already exists' >&2; exit 1; }
pg_dump --format=custom --file="$prefix.dump.partial"
pg_restore --list "$prefix.dump.partial" >/dev/null
mv "$prefix.dump.partial" "$prefix.dump"
(cd "$(dirname "$prefix")" && sha256sum "$(basename "$prefix").dump") > "$prefix.dump.sha256"
{
  pg_dump --version
  psql -X -v ON_ERROR_STOP=1 -Atc "SELECT version(); SELECT extversion FROM pg_extension WHERE extname='timescaledb';"
} > "$prefix.versions.txt"
echo "Backup complete: $prefix.dump"

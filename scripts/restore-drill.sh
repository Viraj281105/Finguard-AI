#!/usr/bin/env bash
# End-to-end PostgreSQL 18 backup + scratch database restore + row-count/MD5 checksum verification drill (S0-09).
# Usage: ./scripts/restore-drill.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${PG_CONTAINER:-finguard-postgres}"
SOURCE_DB="${POSTGRES_DB:-finguard}"
DB_USER="${POSTGRES_USER:-finguard_user}"
SCRATCH_DB="${SOURCE_DB}_restore_drill"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"; docker exec "${CONTAINER}" psql -U "${DB_USER}" -d postgres -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null 2>&1 || true' EXIT

START_MS=$(date +%s%3N)
DUMP_PATH="$("${ROOT_DIR}/scripts/backup-db.sh" "${TMP_DIR}" | tail -n 1 | sed 's/^Backup complete: //')"

echo "Creating scratch database '${SCRATCH_DB}'..."
docker exec "${CONTAINER}" psql -U "${DB_USER}" -d postgres -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};"
docker exec "${CONTAINER}" psql -U "${DB_USER}" -d postgres -c "CREATE DATABASE ${SCRATCH_DB};"

echo "Restoring ${DUMP_PATH} into '${SCRATCH_DB}'..."
RESTORE_START_MS=$(date +%s%3N)
docker exec -i "${CONTAINER}" pg_restore --no-owner --no-privileges --exit-on-error -U "${DB_USER}" -d "${SCRATCH_DB}" < "${DUMP_PATH}"
RESTORE_END_MS=$(date +%s%3N)

TABLES=$(docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${SOURCE_DB}" -Atc \
  "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;")

for tbl in ${TABLES}; do
  SRC_SIG=$(docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${SOURCE_DB}" -Atc \
    "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY 1)), 'EMPTY') FROM (SELECT * FROM ${tbl} ORDER BY 1) t;")
  DST_SIG=$(docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${SCRATCH_DB}" -Atc \
    "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY 1)), 'EMPTY') FROM (SELECT * FROM ${tbl} ORDER BY 1) t;")
  if [[ "${SRC_SIG}" != "${DST_SIG}" ]]; then
    echo "CHECKSUM MISMATCH on table ${tbl}: source=${SRC_SIG} restored=${DST_SIG}" >&2
    exit 1
  fi
  echo "Verified table ${tbl}: ${DST_SIG}"
done

TOTAL_MS=$((RESTORE_END_MS - START_MS))
RTO_MS=$((RESTORE_END_MS - RESTORE_START_MS))
echo "Restore drill PASSED (pg_restore RTO: ${RTO_MS} ms, total drill: ${TOTAL_MS} ms)."


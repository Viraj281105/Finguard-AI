#!/usr/bin/env bash
# Creates a PostgreSQL 18 custom-format backup (-Fc) and SHA-256 manifest (S0-09).
# Usage: ./scripts/backup-db.sh [output_dir]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-${ROOT_DIR}/.backups}"
umask 077   # dumps are readable by the current user only
mkdir -p "${OUT_DIR}"

CONTAINER="${PG_CONTAINER:-finguard-postgres}"
DB_NAME="${POSTGRES_DB:-finguard}"
DB_USER="${POSTGRES_USER:-finguard_user}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="${OUT_DIR}/${DB_NAME}-${STAMP}.dump"

echo "Backing up database '${DB_NAME}' from container '${CONTAINER}' to ${DUMP_FILE}..."
docker exec -i "${CONTAINER}" pg_dump -Fc --no-owner --no-privileges -U "${DB_USER}" -d "${DB_NAME}" > "${DUMP_FILE}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${DUMP_FILE}" > "${DUMP_FILE}.sha256"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "${DUMP_FILE}" > "${DUMP_FILE}.sha256"
fi

echo "Backup complete: ${DUMP_FILE}"


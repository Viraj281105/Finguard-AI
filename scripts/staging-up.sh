#!/usr/bin/env bash
# Brings up an isolated containerised STAGING stack with SYNTHETIC data only (S0-08).
# Nothing is exposed beyond 127.0.0.1.
# Usage: ./scripts/staging-up.sh        Tear down with: ./scripts/staging-down.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${ROOT_DIR}/.staging"
ENV_FILE="${STATE_DIR}/staging.env"

mkdir -p "${STATE_DIR}"

generate_secret() {
  local bytes="$1"
  head -c "${bytes}" /dev/urandom | base64 | tr -d '=\n' | tr '+/' 'AB'
}

PG_PORT="${STAGING_PG_PORT:-15433}"
AI_PORT="${STAGING_AI_PORT:-18000}"
API_PORT="${STAGING_API_PORT:-18080}"
WEB_PORT="${STAGING_WEB_PORT:-14173}"

if [[ ! -f "${ENV_FILE}" ]]; then
  cat > "${ENV_FILE}" <<EOF
POSTGRES_DB=finguard_staging
POSTGRES_USER=finguard_staging
POSTGRES_PASSWORD=$(generate_secret 24)
AI_INTERNAL_TOKEN=$(generate_secret 32)
JWT_SECRET=$(generate_secret 48)
STAGING_PG_PORT=${PG_PORT}
STAGING_AI_PORT=${AI_PORT}
STAGING_API_PORT=${API_PORT}
STAGING_WEB_PORT=${WEB_PORT}
EOF
fi

echo "Starting containerised staging stack (project: finguard-staging, profile: full)..."
cd "${ROOT_DIR}"
docker compose -p finguard-staging --profile full --env-file "${ENV_FILE}" \
  -f docker-compose.yml -f docker-compose.staging.yml up -d --build --wait

echo "Frontend: http://127.0.0.1:${WEB_PORT}   Backend: http://127.0.0.1:${API_PORT}   AI: http://127.0.0.1:${AI_PORT}/health"
echo "Synthetic data only. State file: ${ENV_FILE}"

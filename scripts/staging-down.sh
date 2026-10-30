#!/usr/bin/env bash
# Stops the containerised STAGING stack started by staging-up.sh and removes its volume and state (S0-08).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${ROOT_DIR}/.staging"
ENV_FILE="${STATE_DIR}/staging.env"

cd "${ROOT_DIR}"
if [[ -f "${ENV_FILE}" ]]; then
  docker compose -p finguard-staging --profile full --env-file "${ENV_FILE}" \
    -f docker-compose.yml -f docker-compose.staging.yml down -v --remove-orphans
else
  docker compose -p finguard-staging --profile full \
    -f docker-compose.yml -f docker-compose.staging.yml down -v --remove-orphans
fi

rm -rf "${STATE_DIR}"

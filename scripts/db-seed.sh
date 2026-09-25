#!/usr/bin/env bash
# Load SYNTHETIC dev data. Run after the backend has started once (Flyway migrates on startup).
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d schoomano -f /schoomano-db/seed/dev_seed.sql'

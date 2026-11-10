#!/bin/sh
# Load SYNTHETIC demo data into the local database. Safe to run repeatedly.
# The backend must have started once (it creates the schema with Flyway).
set -eu
. "$(dirname -- "$0")/lib/common.sh"
load_env
require_docker
ready=$(psql_in_db -tA -c "select to_regclass('public.tenant') is not null" 2>/dev/null || true)
[ "$(printf '%s' "$ready" | tr -d '[:space:]')" = t ] || die "Schema not found. Start the backend first: scripts/start.sh"
info "Loading synthetic seed data"
# Every seed file in name order (10_base.sql first; numeric prefixes keep the order the same in every locale); each is guarded and safe to repeat.
for f in backend/db/seed/*.sql; do
  psql_in_db -q -f "/schoomano-db/seed/$(basename "$f")"
done
info "Seed complete. Sign in as Asha, Ravi, Farah, Meera, Karan or Other (all synthetic)."

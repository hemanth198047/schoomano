#!/usr/bin/env bash
# Database invariant tests (RLS isolation, audit chain, effective dating). Runs inside a transaction and rolls back.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d schoomano -f /schoomano-db/tests/rls_and_invariants_test.sql -f /schoomano-db/tests/app_queries_smoke_test.sql'

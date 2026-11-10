#!/bin/sh
# Database tests: RLS isolation, audit chain, effective dating, and the API's SQL. Rolls back; needs seed data.
set -eu
. "$(dirname -- "$0")/lib/common.sh"
load_env
require_docker
psql_in_db -q -f /schoomano-db/tests/rls_and_invariants_test.sql -f /schoomano-db/tests/academic_structure_test.sql -f /schoomano-db/tests/app_queries_smoke_test.sql

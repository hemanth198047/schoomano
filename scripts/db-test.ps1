# Database invariant tests (RLS isolation, audit chain, effective dating). Rolls back after running.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d schoomano -f /schoomano-db/tests/rls_and_invariants_test.sql -f /schoomano-db/tests/app_queries_smoke_test.sql'
if ($LASTEXITCODE -ne 0) { throw "Database tests failed" }

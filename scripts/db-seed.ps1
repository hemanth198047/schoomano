# Load SYNTHETIC dev data. Run after the backend has started once (Flyway migrates on startup).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d schoomano -f /schoomano-db/seed/dev_seed.sql'
if ($LASTEXITCODE -ne 0) { throw "Seeding failed" }

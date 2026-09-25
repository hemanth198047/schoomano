# Destroys the LOCAL database volume. Never point this at a shared environment.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose down -v
docker compose up -d postgres

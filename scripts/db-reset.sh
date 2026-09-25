#!/usr/bin/env bash
# Destroys the LOCAL database volume. Never point this at a shared environment.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down -v
docker compose up -d postgres

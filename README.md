# Schoomano

Multi-tenant school operations platform for Indian K-12 school groups. This repository is at **Stage A (foundation)**: tenancy with PostgreSQL row-level security, identity/roles/guardian relationships, authorization policy, effective-dated configuration and feature flags, audit, the admin web shell and design system.

Read first: [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) (what is real, verified, or untested), [`docs/SPEC_BASELINE.md`](docs/SPEC_BASELINE.md) (open decisions), [`docs/BACKLOG.md`](docs/BACKLOG.md), [`docs/adr/`](docs/adr).

> All data in this repository is synthetic. Do not load real student or guardian data into any environment until the pilot checklist in `docs/RELEASE_CHECKLIST.md` is complete.

## Layout

```
backend/schoomano-core   framework-free rules: tenancy scopes, access policy, config resolution (+ unit tests)
backend/schoomano-app    Spring Boot API, Flyway migrations, security (+ Testcontainers tests)
db/seed, db/tests        synthetic seed data; SQL tests for RLS and invariants
packages/design-tokens   design tokens → CSS variables and a TS object
web                      React admin web app
docs                     status, ADRs, API contract, security, design system
```

## Prerequisites

JDK 21, Maven 3.9+, Docker Desktop (or Docker Engine) with Compose, Node.js 22+ and npm.

## First run

### Windows (PowerShell)

```powershell
# 1. Configuration and database
Copy-Item .env.example .env          # then edit the two passwords in .env
docker compose up -d postgres

# 2. Backend (terminal 1): builds, runs Flyway migrations, starts on http://localhost:8080
. .\scripts\load-env.ps1
cd backend
mvn -q install -DskipTests
mvn -pl schoomano-app spring-boot:run

# 3. Synthetic seed data (terminal 2, repo root, after the backend has started once)
.\scripts\db-seed.ps1

# 4. Web (terminal 2)
npm install
npm run dev:web                      # open http://localhost:5173
```

### macOS / Linux (bash)

```bash
# 1. Configuration and database
cp .env.example .env                 # then edit the two passwords in .env
docker compose up -d postgres

# 2. Backend (terminal 1)
set -a; source .env; set +a
cd backend
mvn -q install -DskipTests
mvn -pl schoomano-app spring-boot:run

# 3. Synthetic seed data (terminal 2, repo root)
./scripts/db-seed.sh

# 4. Web (terminal 2)
npm install
npm run dev:web                      # open http://localhost:5173
```

On the sign-in page choose a synthetic user: **Asha** (group admin), **Ravi** (teacher), **Farah** (safeguarding lead), **Meera** (guardian of two children on two campuses), **Karan** (separated guardian, notices only) or **Other** (a different school group).

Quick API check (with the backend running):

```bash
curl -H "Authorization: Bearer dev:dev-meera" http://localhost:8080/api/v1/me
```
```powershell
Invoke-RestMethod -Headers @{ Authorization = "Bearer dev:dev-meera" } http://localhost:8080/api/v1/me
```

## Tests

| What | PowerShell | bash |
|---|---|---|
| Backend unit + API tests (needs Docker) | `cd backend; mvn verify` | `cd backend && mvn verify` |
| Database RLS/invariant + query smoke tests | `.\scripts\db-test.ps1` | `./scripts/db-test.sh` |
| Web: tokens, types, lint, format, tests incl. accessibility, build | `npm run check` | `npm run check` |
| Core rules without Maven (fallback) | n/a | `./scripts/verify-core-without-maven.sh` |

Reset the local database (destroys local data): `.\scripts\db-reset.ps1` or `./scripts/db-reset.sh`, then restart the backend and re-seed.

## Configuration

See `.env.example`. Production uses OIDC (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`) and must never set `SCHOOMANO_AUTH_DEV_MODE`; the app refuses to start if dev mode is combined with the `prod` profile.

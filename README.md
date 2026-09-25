# Schoomano

School operations platform for Indian K-12 schools. Pilot: one premium school running CBSE and State-board programmes, alongside its current system for one term.

This repository is at **Stage A (foundation)**. Read [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) for what is verified, what is only implemented, and what has never run.

> All data here is synthetic. Do not load real student or guardian data anywhere until the pilot gates in `docs/RELEASE_CHECKLIST.md` are complete (including DPDP legal review).

## Layout

```
backend/     one Spring Boot project (Java 21): API, migrations, synthetic seed, DB tests → backend/README.md
frontend/    one React project: web app and design tokens, served by nginx               → frontend/README.md
infra/       docker-compose.yml for the full local stack; PostgreSQL init
scripts/     start / stop / status / logs / seed / db-test  (.sh, .ps1, .cmd)
docs/        status, decisions (ADRs), backlog, API contract, security, design system
```

Frontend and backend are independent: separate builds, separate images, connected only through `/api/v1` ([ADR 0008](docs/adr/0008-separate-frontend-backend-and-runtime.md)).

## Quick start

The only prerequisite for the default mode is **Docker** (Docker Desktop on Windows/macOS, Docker Engine with the compose plugin on Linux). The first start builds both images and takes several minutes; later starts are fast. A `.env` with random local passwords is created on first run.

**Windows** (Command Prompt or PowerShell; the `.cmd` wrappers avoid execution-policy prompts):
```bat
scripts\start.cmd -Seed
scripts\status.cmd
scripts\stop.cmd
```

**macOS, Linux, WSL, Git Bash:**
```sh
./scripts/start.sh --seed
./scripts/status.sh
./scripts/stop.sh
```

**Windows: "script is not digitally signed"?** Files from a downloaded zip are marked as coming from the internet, and PowerShell's default policy blocks them. Use the `.cmd` wrappers above, or unblock the project once:
```powershell
Get-ChildItem -Path . -Recurse | Unblock-File
```
If your organisation enforces the policy through Group Policy, only unblocking works.

Then open **http://localhost:8081** and pick a synthetic user: Asha (school admin), Ravi (teacher), Farah (safeguarding lead), Meera (parent of two), Karan (separated parent, notices only), Other (a different school).

## Script reference

Options work in either style on every platform (`--dev` or `-Dev`).

| Command (`scripts/…` .sh / .cmd / .ps1) | What it does |
|---|---|
| `start` | Database, backend and frontend in containers. Web on :8081, API on :8080 by default |
| `start --dev` | Database in a container; backend (`java -jar`) and frontend (Vite, hot reload on :5173 by default) as local processes. Needs JDK 21, Maven 3.9+, Node 22 |
| `start [--dev] db\|backend\|frontend` | Start one component and what it depends on |
| `start --seed` / `seed` | Load synthetic demo data (safe to repeat) |
| `start --no-build` | Reuse existing images / backend jar |
| `stop` | Stop local processes and containers; database kept |
| `stop backend` | Stop one component |
| `stop --purge` | Also delete the local database volume (asks you to type `delete`; `--yes` skips) |
| `status` | Local processes, containers, health endpoints |
| `logs [backend\|frontend\|db] [-f]` | Recent logs, optionally following |
| `db-test` | RLS isolation, audit chain, effective dating, and API-query tests against the local database |

**Ports.** Defaults are 5432 (database), 8080 (API), 8081 (web) and 5173 (dev web server), all bound to 127.0.0.1. If another program already uses one when you run `start`, the script picks the next free port, tells you, and saves it in `.env`, so `status`, `stop` and later starts use the same ports. Ports held by Schoomano's own running containers or processes are never moved. To choose ports yourself, edit them in `.env`.

**Paths.** The scripts can be run from any folder. Each one switches to the repository root and uses only relative paths (`.env`, `infra/docker-compose.yml`, `.run/`). The PowerShell scripts return you to your original folder and restore any environment variables they set, even after an error.

**Docker output.** The scripts ask Docker Compose for plain progress output, so the build log prints as text lines. This avoids "failed to get console: The handle is invalid" on Windows and in Git Bash.

## Tests

| What | Command |
|---|---|
| Backend unit + API tests (needs Docker) | `cd backend && mvn verify` |
| Frontend: tokens, types, lint, format, tests incl. accessibility, build | `cd frontend && npm ci && npm run check` |
| Database tests on the running stack | `scripts/db-test.sh` or `scripts\db-test.cmd` |

CI (`.github/workflows/ci.yml`) runs these plus script linting, Compose validation, image builds and a secrets scan.

## Without the scripts

```sh
cp .env.example .env    # set the two passwords
docker compose --env-file .env -f infra/docker-compose.yml -p schoomano up -d --build
```

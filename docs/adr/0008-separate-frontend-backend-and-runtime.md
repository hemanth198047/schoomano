# ADR 0008: Separate frontend and backend; same-origin runtime; scripted local operations

Status: Accepted (2026-09-25)

## Context
The first layout mixed an npm workspace at the root with the Maven build. The product owner asked for the frontend and backend to be separated, and for start/stop tooling on Unix-like systems and Windows.

## Decision
- **`backend/`** is self-contained: Maven build, Flyway migrations, synthetic seed, database tests, Dockerfile. Needs JDK 21 + Maven, or only Docker.
- **`frontend/`** is self-contained: one npm package (React, Vite, design tokens in `frontend/design-tokens`), Dockerfile. Needs Node 22, or only Docker.
- The two meet only over HTTP at `/api/v1/**` as described by `docs/api/openapi.yaml`. Neither imports code from the other.
- **Same-origin runtime**: the browser always calls `/api/...` on the page's own origin. Vite proxies in development; unprivileged nginx proxies in the container and serves the static build with a strict Content-Security-Policy. No API URL is baked into the build and CORS is not needed.
- **`infra/docker-compose.yml`** runs PostgreSQL, backend and frontend, bound to 127.0.0.1.
- **`scripts/`** provides `start`, `stop`, `status`, `logs`, `seed`, `db-test` as POSIX `sh` (Linux, macOS, WSL, Git Bash), PowerShell (Windows PowerShell 5.1 and PowerShell 7), and `.cmd` wrappers for cmd.exe. Two modes: all-in-containers (default) or `--dev` (database in a container; backend and frontend as local processes tracked by pid files, with pid-reuse protection).

## Consequences
- Frontend and backend can be versioned, built and deployed independently; the API contract is the coupling point and must stay backward compatible within `/api/v1`.
- The mobile app will need the design tokens: they move to a published package at that point (ADR 0005).
- The container images are the deployment unit for any cloud (D6 is still open), with managed PostgreSQL replacing the compose database in shared environments.

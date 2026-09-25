# Implementation status

Living document, updated at the end of every slice. Last update: **2026-09-25, Stage A + repository separation and local operations**.

Status values: **Not started**, **In progress**, **Implemented** (code complete, tests written), **Verified** (tests executed and passing, evidence linked), **Deferred** (deliberately postponed, reason given).

Nothing below claims production readiness. No module has been used by a real school. All data is synthetic.

## Foundation and platform

| Capability | Status | Evidence |
|---|---|---|
| Tenant isolation (RLS, FORCE, app role without BYPASSRLS, fail-closed without tenant) | **Verified** | `backend/db/tests/rls_and_invariants_test.sql` T1–T4, T11–T12 on PostgreSQL 16; mutation check: disabling RLS makes T1 fail |
| Append-only, hash-chained, tenant-isolated audit log | **Verified** | T5, T12 |
| Effective-dated config with history, no backdating, overlap exclusion | **Verified** | T6, T7; `ConfigResolverTest` (7 tests) |
| Idempotency uniqueness per tenant + scope | **Verified** (table level) | T9. HTTP filter arrives with B6 attendance sync |
| Break-glass constraints | **Verified** | T10; `AccessPolicyTest.RestrictedCases` |
| Authorization policy (plain-Java domain) | **Verified** | `com.schoomano.domain`: 35 JUnit 5 tests incl. `DomainPurityTest` (no Spring/JDBC imports; negative control verified), compiled with `-Xlint:all -Werror` |
| Synthetic seed: re-runnable, CBSE + State board programmes | **Verified** | Fresh database: migrate, seed twice (second run is a no-op), all DB tests pass |
| Spring Boot API: `/api/v1/me`, `/campuses`, `/config/*` | **Implemented, not executed** | Parse-checked with `javac`; every SQL statement executed against the schema as the app role (`backend/db/tests/app_queries_smoke_test.sql`). **Never compiled or started**: Maven Central is unreachable in the build sandbox. `StageAApiIT` (9 Testcontainers tests) written, not run. The first `scripts/start` on a developer machine is its first compile |
| Web app: shell, design system, Today / Campuses / Settings | **Verified** | `cd frontend && npm run check`: token drift + CSS reference check, typecheck, ESLint, Prettier, 26 Vitest tests incl. axe scans with a negative control, production build (~90 kB gzip JS). Screenshots reviewed at 1366 px and 390 px |
| Single-project backend and frontend (ADR 0001, 0008) | **Verified** | One Maven project, one npm package. Frontend builds and tests with no Java; backend domain builds and tests with no Node; the two meet only at `/api` |
| Web container: nginx, `/api` proxy, SPA fallback, CSP and headers | **Verified** | Template rendered as the image does and run with real nginx against the production build: `/healthz`, proxied `/api/v1/me`, deep-link fallback, immutable asset caching, 404 for missing assets, CSP/nosniff/no-referrer present |
| Container images and Compose file | **Implemented, not executed** | Both Dockerfiles pass hadolint; Compose file valid against the Compose Specification schema (with a negative control). **Images not built here** (no Docker in the sandbox); CI builds both |
| Start/stop/status/logs/seed/db-test scripts (sh, PowerShell, cmd) | **Verified with test doubles** | Relative paths from the repo root (run from any folder); automatic free-port selection saved to `.env`, never moving ports held by Schoomano itself; Docker output goes straight to the console with plain progress (fixes "failed to get console" on Windows); PowerShell restores the caller's folder and environment. POSIX scripts: ShellCheck clean, parse under `dash`. PowerShell: parse clean under PowerShell 7.6. Both families behaviour-tested with a stand-in `docker` command (compose calls recorded; `seed` and `db-test` executed for real against PostgreSQL), real Vite in `--dev` mode (start, proxy, status, logs, stop, no double start), stale-pid safety, and failure paths with correct exit codes. **Not yet run against real Docker, and the PowerShell scripts not yet run on Windows itself** |
| CI pipeline (6 jobs) | **Implemented, not executed** | `.github/workflows/ci.yml`; not yet run on GitHub |
| API contract | **Verified** (lint) | `docs/api/openapi.yaml` valid per Redocly CLI |

## Module map M01–M30

| Module | Priority | Status | Notes / evidence |
|---|---|---|---|
| M01 Organization and identity | P0 | **In progress** | Data model, policy engine and `/me` done. Parent phone OTP designed (ADR 0009). Missing: OTP and staff OIDC/MFA implementation (B4), invitations, custom roles UI, relationship admin UI (B3) |
| M02 Admissions CRM | P1 | Not started | `student_enrollment` table exists as a dependency only |
| M03 Student information | P0 | Not started | `person`/`household` base tables exist; B3 |
| M04 Academic structure and calendar | P0 | **In progress** | Academic years with overlap protection (T8); CBSE and State programmes seeded. Per-programme classes, subjects, terms, grade scales, calendar, rollover: B1 |
| M05 Scheduling and substitutions | P0 | Not started | B5 |
| M06 Attendance | P0 | Not started | Config key `attendance.late_after_minutes` exists; B6 |
| M07 Curriculum planning | P1 | Not started | |
| M08 Learning delivery | P1 | Not started | |
| M09 Assessment and reporting | P1 | Not started | Effective-dated config is the prerequisite for historical grade rules |
| M10 Student support and intervention | P1 | Not started | |
| M11 Safeguarding and incident management | P1 | Not started | Restricted-partition policy and break-glass exist in core; no case module |
| M12 Health and wellness | P1 | Not started | `HEALTH_OPERATIONAL_FLAG_READ` vs `HEALTH_RECORD_READ` split exists in policy |
| M13 Parent and student engagement | P0 | Not started | Outbox table exists; B7 |
| M14 Fees and collections | P0 | Not started | Money formatting in paise exists in web; B8 |
| M15 Finance and procurement | P1 | Not started | |
| M16 HR and workforce | P1 | Not started | |
| M17 Transport and child handoff | P1 | Not started | |
| M18 Co-curricular and athletics | P1 | Not started | |
| M19 Trips and events | P1 | Not started | Guardian `GIVE_CONSENT` capability exists |
| M20 Library and learning resources | P2 | Deferred | Phase 3 per spec |
| M21 Facilities, maintenance and visitors | P1 | Not started | |
| M22 Food and nutrition | P2 | Deferred | Phase 3 |
| M23 Hostel or boarding | P2 | Deferred | Phase 3 |
| M24 Compliance and evidence | P1 | Not started | Needs legal inputs (D7) |
| M25 Analytics and decisions | P1 | Not started | |
| M26 Workflow and automation studio | P1 | Not started | Config versioning pattern is the intended basis |
| M27 Integration and data exchange | P0 | **In progress** | Contract-first REST + problem details + correlation ids + outbox table. Missing: webhooks, API keys, idempotency filter, import pipeline (B2) |
| M28 AI assistance optional | P2 | Deferred | `flag.ai.enabled` = false by default; workflows must work without AI |
| M29 Migration and onboarding | P0 | Not started | B2: repeatable CSV imports for one term of coexistence (diff, conflicts, rollback) |
| M30 Support, audit and operations | P0 | **In progress** | Audit log, support-session policy, correlation ids, health endpoints. Missing: support console, tenant-approved support grant UI, metrics dashboards, backups/restore drill |

## Known limitations

1. **The Spring layer has never been compiled or started.** First action on a developer machine: `scripts/start` (container build compiles it) and `cd backend && mvn verify`. Fix whatever that reveals before building features.
2. The scripts have been behaviour-tested with a Docker stand-in on Linux and PowerShell 7 on Linux; first real runs on Windows (5.1 and 7) and macOS may surface environment differences.
3. The campus switcher changes the scope bar but does not filter lists yet (single-school pilot makes this low priority).
4. No production sign-in yet: parent OTP (ADR 0009) and staff OIDC are slice B4.
5. Hindi strings are partial and need native review; the State-board language (e.g. Kannada, Tamil) depends on which state is chosen.
6. No mobile app yet (ADR 0005 is Proposed).
7. `config_version.value` is not schema-validated per key yet; the web validates the two known keys.

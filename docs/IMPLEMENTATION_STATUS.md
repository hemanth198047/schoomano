# Implementation status

Living document. Updated at the end of every slice. Last update: **2026-09-25, Stage A (foundation)**.

Status values: **Not started**, **In progress**, **Implemented** (code complete, tests written), **Verified** (tests executed and passing, evidence linked), **Deferred** (deliberately postponed, reason given).

Nothing below claims production readiness. No module has been used by a real school. All data is synthetic.

## What Stage A delivers (cross-cutting foundation)

| Capability | Status | Evidence |
|---|---|---|
| Tenant isolation (RLS, FORCE, app role without BYPASSRLS, fail-closed without tenant) | **Verified** | `db/tests/rls_and_invariants_test.sql` T1–T4, T11–T12 pass on PostgreSQL 16; mutation check: disabling RLS makes T1 fail |
| Append-only, hash-chained, tenant-isolated audit log | **Verified** | T5, T12 |
| Effective-dated config with history, no backdating, overlap exclusion | **Verified** | T6, T7 (DB); `ConfigResolverTest` 7 tests (core) |
| Idempotency uniqueness per tenant + scope | **Verified** (table level) | T9. HTTP filter not wired yet |
| Break-glass constraints (justification ≥ 20 chars, ≤ 4 h) | **Verified** | T10 (DB), `AccessPolicyTest.RestrictedCases` (core) |
| Authorization policy (tenant, restricted partitions, scopes, guardian capabilities, support sessions) | **Verified** | `schoomano-core`: 34 JUnit 5 tests pass, compiled with `-Xlint:all -Werror` |
| Spring Boot API: `/api/v1/me`, `/campuses`, `/config/*` | **Implemented, not executed** | Parse-checked with `javac`; every SQL statement executed against the schema as the app role (`db/tests/app_queries_smoke_test.sql`). **Not compiled or run here** (Maven Central unreachable in the build sandbox). `StageAApiIT` (9 Testcontainers tests) written, not run |
| Correlation ids, problem-details errors, dev-token guard against prod profile | **Implemented, not executed** | Same limitation as above |
| Web admin shell, design system, i18n scaffold, Today / Campuses / Settings pages | **Verified** | `npm run check`: tokens + CSS-reference check, typecheck, ESLint, Prettier, **26 Vitest tests** incl. axe scans (with a negative control proving axe detects violations), production build. Screenshots reviewed at 1366 px and 390 px |
| CI pipeline | **Implemented, not executed** | `.github/workflows/ci.yml` (backend verify, DB tests, web check, gitleaks). Not yet run on GitHub |
| API contract | **Verified** (lint) | `docs/api/openapi.yaml` valid per Redocly CLI (2 expected warnings) |

## Module map M01–M30

| Module | Priority | Status | Notes / evidence |
|---|---|---|---|
| M01 Organization and identity | P0 | **In progress** | Data model (identity, role_grant, guardian_relationship with capabilities, break-glass, support grants), policy engine and `/me` done. Missing: OIDC wiring with a real IdP (D3), MFA, invitations, recovery, custom roles UI, relationship admin UI |
| M02 Admissions CRM | P1 | Not started | `student_enrollment` table exists as a dependency only |
| M03 Student information | P0 | Not started | `person`/`household` base tables exist; B3 |
| M04 Academic structure and calendar | P0 | **In progress** | Academic years with overlap protection (T8). Classes, sections, subjects, terms, calendars, rollover: B1 |
| M05 Scheduling and substitutions | P0 | Not started | B4 |
| M06 Attendance | P0 | Not started | Config key `attendance.late_after_minutes` exists; B5 |
| M07 Curriculum planning | P1 | Not started | |
| M08 Learning delivery | P1 | Not started | |
| M09 Assessment and reporting | P1 | Not started | Effective-dated config is the prerequisite for historical grade rules |
| M10 Student support and intervention | P1 | Not started | |
| M11 Safeguarding and incident management | P1 | Not started | Restricted-partition policy and break-glass exist in core; no case module |
| M12 Health and wellness | P1 | Not started | `HEALTH_OPERATIONAL_FLAG_READ` vs `HEALTH_RECORD_READ` split exists in policy |
| M13 Parent and student engagement | P0 | Not started | Outbox table exists; B6 |
| M14 Fees and collections | P0 | Not started | Money formatting in paise exists in web; B7 |
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
| M29 Migration and onboarding | P0 | Not started | B2 (CSV import with preview, dry-run and idempotent commit) |
| M30 Support, audit and operations | P0 | **In progress** | Audit log, support-session policy, correlation ids, health endpoints. Missing: support console, tenant-approved support grant UI, metrics dashboards, backups/restore drill |

## Known limitations (Stage A)

1. **The Spring layer has never been compiled or started.** First local action: `cd backend && mvn verify` (needs Docker for `StageAApiIT`). Fix whatever that reveals before building anything else.
2. The campus switcher changes the scope bar but does not filter lists yet; the first list that needs it is M04 classes.
3. No production sign-in: OIDC is configured in code but untested against an IdP; dev tokens only work with `SCHOOMANO_AUTH_DEV_MODE=true`.
4. Hindi strings are partial and need native review; Kannada/Tamil not started.
5. No mobile app yet (ADR 0005 is Proposed).
6. `config_version.value` is not schema-validated per key yet; the web validates the two known keys.

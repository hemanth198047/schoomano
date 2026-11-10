# Database migrations

- Tool: Flyway, files in `backend/src/main/resources/db/migration`, named `V<n>__<description>.sql`. Never edit an applied migration; add a new one.
- **Roles**: migrations run as the owner (`SCHOOMANO_DB_OWNER_USER`); the application runs as `schoomano_app`, which owns nothing and has `NOBYPASSRLS`. In local Docker the owner is the container superuser. **In shared environments the owner must be a non-superuser role** that owns the schema, so FORCE RLS also applies to it.
- Every new tenant table must: carry `tenant_id`, use composite FKs `(tenant_id, id)`, `ENABLE` + `FORCE` RLS with the standard policy, grant only needed privileges to `schoomano_app`, and get a test in `db/tests/`.
- Expand/contract for breaking changes: add column → backfill → switch code → drop in a later release.
- Seeds in `backend/db/seed/` are re-runnable (guarded), synthetic and for development only. Never run them in a shared environment.

| Version | Content |
|---|---|
| V1 | Tenancy hierarchy, person/identity, role grants, households, guardian relationships, enrollments, break-glass and support grants, effective-dated config, hash-chained audit, outbox, idempotency |
| V2 | RLS on all tenant tables, app-role grants, `auth_resolve_identity()`, `config_publish()` |
| V3 | B1 academic structure: `grade_level`, `subject` (per programme); `academic_term` (per programme and year, no overlap, inside the year), `class_section`, `calendar_event` (per year); `student_enrollment.class_section_id`; `academic_consistency()` trigger (dates inside the year, programme on the year's campus); RLS and grants. Seed: `db/seed/dev_seed_academic.sql` (separately guarded, so existing local databases pick it up) |
| V4 | Year lifecycle and rollover: one ACTIVE year per campus (partial unique index); `academic_year.rolled_from` (a year rolls over once); `student_enrollment.promoted_from` |

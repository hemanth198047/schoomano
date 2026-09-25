# ADR 0002: Tenant isolation enforced by PostgreSQL row-level security

Status: Accepted (2026-09-25)

## Context
The worst failure for this product is one school's child data appearing in another school's session (spec §14: "no learner from another tenant leaks through API, search, file URL or analytics"). Application-only filtering fails the first time a developer forgets a `WHERE tenant_id = ?`.

## Decision
- Shared schema, `tenant_id` on every tenant-owned table, composite foreign keys `(tenant_id, id)` so a row can never reference another tenant's row.
- RLS `ENABLE` + `FORCE` on every tenant table, policy `tenant_id = app_current_tenant()` for both reads (`USING`) and writes (`WITH CHECK`).
- The application connects as `schoomano_app` (no ownership, `NOBYPASSRLS`). Migrations run as a separate owner role.
- `TenantTx` sets `app.tenant_id` with `set_config(..., true)` (transaction-local, so pooled connections cannot leak it) from the **server-resolved** actor.
- With no tenant set, queries return nothing (fail closed).
- The only cross-tenant read is `auth_resolve_identity()`, a narrow `SECURITY DEFINER` function used at sign-in.

## Consequences
- Verified by `db/tests/rls_and_invariants_test.sql` (12 tests, run as the app role) including a mutation check that proved the tests fail when RLS is disabled.
- Analytics across a group (M25) must run per-tenant or through an explicitly designed reporting role; no BYPASSRLS role for the app.
- Large-tenant performance: indexes lead with `tenant_id`; revisit partitioning only with load-test evidence.

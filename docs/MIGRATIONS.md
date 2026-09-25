# Database migrations

- Tool: Flyway, files in `backend/schoomano-app/src/main/resources/db/migration`, named `V<n>__<description>.sql`. Never edit an applied migration; add a new one.
- **Roles**: migrations run as the owner (`SCHOOMANO_DB_OWNER_USER`); the application runs as `schoomano_app`, which owns nothing and has `NOBYPASSRLS`. In local Docker the owner is the container superuser. **In shared environments the owner must be a non-superuser role** that owns the schema, so FORCE RLS also applies to it.
- Every new tenant table must: carry `tenant_id`, use composite FKs `(tenant_id, id)`, `ENABLE` + `FORCE` RLS with the standard policy, grant only needed privileges to `schoomano_app`, and get a test in `db/tests/`.
- Expand/contract for breaking changes: add column → backfill → switch code → drop in a later release.
- Seeds in `db/seed/` are synthetic and for development only. Never run them in a shared environment.

| Version | Content |
|---|---|
| V1 | Tenancy hierarchy, person/identity, role grants, households, guardian relationships, enrollments, break-glass and support grants, effective-dated config, hash-chained audit, outbox, idempotency |
| V2 | RLS on all tenant tables, app-role grants, `auth_resolve_identity()`, `config_publish()` |

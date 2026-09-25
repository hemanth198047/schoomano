# ADR 0004: Effective-dated, inherited configuration; feature flags are configuration

Status: Accepted (2026-09-25)

## Decision
- `config_version` rows: key, scope (GROUP or CAMPUS now; PROGRAMME/YEAR/CLASS later), version, JSON value, `[effective_from, effective_to)`, status DRAFT/PUBLISHED/RETIRED, change note, creator, publisher.
- Published windows for one key+scope cannot overlap (GiST exclusion constraint).
- `config_publish()` closes the open version at the new effective date and **refuses backdating** before the latest published version, so historical reports re-resolve the rule that applied then (spec §14 grade-rule scenario).
- Resolution: narrowest scope in the target path wins; UI shows whether a value is inherited.
- Preview: `ConfigResolver.preview` shows per-campus before/after (seed of the spec's "policy simulator").
- Rollback = publish a new version carrying the earlier value. History is never deleted.
- Feature flags use the same mechanism under `flag.*` keys (e.g. `flag.ai.enabled`, default false), giving flags versioning, preview, audit and rollback for free.

## Consequences
- Changes apply without redeploy. Hot-reload caching (with outbox-driven invalidation) is a Stage B concern once reads are frequent.

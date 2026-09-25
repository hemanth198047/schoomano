# ADR 0003: Authorization = tenant → restricted partition → role scope → guardian relationship

Status: Accepted (2026-09-25)

## Decision
`AccessPolicy.decide(actor, permission, resource, now)` in `schoomano-core`, deny by default, in this order:

1. **Tenant**: actor and resource tenants must match. Always audited on mismatch.
2. **Support**: platform support acts only under a tenant-approved, time-limited `SupportSession`, with read-only operational permissions, never restricted data (M30).
3. **Restricted partitions** (`HEALTH`, `SAFEGUARDING`): reachable only by (a) the case team, (b) a role that explicitly carries the domain permission in scope (e.g. `safeguarding_lead` on that campus), or (c) a break-glass grant for that single record, max 4 hours, justification ≥ 20 characters, review required. Seniority alone (e.g. `group_admin`) grants nothing here. Ordinary permissions such as `STUDENT_READ_BASIC` never open a restricted record, so search cannot surface case details.
4. **Role scope**: time-bounded grants at GROUP/LEGAL_ENTITY/CAMPUS/PROGRAMME/ACADEMIC_YEAR/CLASS apply to resources whose scope path contains the grant's node.
5. **Guardian relationship**: explicit per-relationship capabilities (attendance, academic, fees, consent, notices, pickup). A `RESTRICTED` relationship keeps only what is left switched on, e.g. a separated parent who receives notices but cannot see records, pay or consent.

Permissions are code-defined enums; roles are configurable bundles of them. A person can hold staff grants and guardian links at once.

Denied reads of specific records return 404 to prevent enumeration; denied actions in a visible scope return 403.

## Evidence
34 unit tests in `AccessPolicyTest`, `ScopePathTest`, `ConfigResolverTest` cover the spec §14 scenarios that are expressible at this layer.

## Open
Custom tenant roles (UI + table) arrive with M01 in Stage B. Class-level teacher scoping arrives with M04 classes.

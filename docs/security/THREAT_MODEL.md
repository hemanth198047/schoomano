# Threat model (Stage A)

Scope: multi-tenant web API, PostgreSQL, web app behind nginx, parent phone sign-in (designed). Data: children's identity, guardianship, attendance, and later health, safeguarding and payments. Method: STRIDE per asset, focused on harms to children and schools.

| # | Threat | Impact | Control in Stage A | Status / remaining |
|---|---|---|---|---|
| 1 | Cross-tenant data exposure through a missing filter | Severe (child data to another school) | RLS FORCE on every table, app role without BYPASSRLS, fail-closed without tenant, composite FKs | Verified by DB tests; add per-endpoint cross-tenant tests for each new API |
| 2 | Client picks another tenant by editing a header | Severe | Tenant resolved from identity; header only selects among the identity's memberships | Implemented; IT written, not run |
| 3 | Guardian sees another child, or a separated parent sees restricted data | Severe, possible physical risk | Relationship-scoped capabilities; RESTRICTED status keeps only what is switched on; 404 on denied record reads | Verified in core policy tests |
| 4 | Staff or admin browses safeguarding or health records | Severe | Restricted partitions ignore seniority; case team / explicit role / break-glass only; every access audited | Verified in policy; case module not built |
| 5 | Break-glass abused as a routine back door | High | Per record, ≤ 4 h, justification ≥ 20 chars, review required, audited | DB + policy verified; review workflow UI in Phase 2 |
| 6 | Platform support staff overreach | High | Support sessions: tenant-granted, time-limited, operational read only, never restricted data | Policy verified; support console B9 |
| 7 | Dev token accepted in production | Severe | Dev filter registered only with dev-mode; startup fails if combined with `prod` profile | Implemented; add a deployment check that `SCHOOMANO_AUTH_DEV_MODE` is unset |
| 8 | Audit trail altered to hide access | High | Append-only trigger (no UPDATE/DELETE), per-tenant hash chain | Verified T5; external anchoring of chain heads is a Phase 2 option |
| 9 | Sensitive data in logs | High | No request bodies logged; problem details omit internals; audit details must not contain health or case text | Enforced by convention; add a log-scrubbing test before M11/M12 |
| 10 | Token theft from browser storage | Medium | Tokens in memory only; no localStorage | Implemented |
| 11 | Replay or duplicate submissions (payments, attendance) | High (money, records) | Idempotency table unique per tenant+scope+key | Table verified; HTTP filter with first retried write |
| 12 | Enumeration of student ids | Medium | UUIDs, 404 for denied reads | Implemented |
| 13 | Secrets committed | High | `.env` git-ignored, `.env.example` only, gitleaks in CI | Implemented; CI not yet run |
| 14 | File URL leakage (documents, photos) | Severe | Not applicable yet | Design for B3: private bucket, short-lived signed URLs, malware scan, tenant prefix checks |
| 15 | OTP brute force or SMS pumping (attacker triggers costly SMS) | High (account takeover, cost) | Design in ADR 0009: hashed 6-digit codes, 5 attempts, per-number/IP limits, daily caps, same response for unknown numbers | B4 |
| 16 | Parent phone number hijack (SIM swap, number change) | Severe (child data) | Number changes only by school office with reason + audit; new-device notices; revocable sessions | B4 |
| 17 | Script injection in the web app | High | Unprivileged nginx with strict CSP (`default-src 'self'`, no inline script), `nosniff`, `no-referrer`, `frame-ancestors 'none'`; React escaping | Verified live against the production build |
| 18 | Dev sign-in shipped in a shared build | Severe | `VITE_DEV_AUTH` is a build argument defaulting to false; backend dev mode refused under `prod`; release checklist gate | Implemented |

Before any real child data: DPDP counsel review of consent and guardian flows, a signed DPA with the school, penetration test, and restore drill (see `RELEASE_CHECKLIST.md`).

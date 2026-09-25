# Specification baseline

Source: `School_Operations_Platform_Requirements_Specification.docx` (read in full on 2026-09-25) and the build prompt. Product name: **Schoomano**.

## Inventory

Sections read: Executive product decision; 1 Market comparison (Fedena, Entab CampusCare, MyClassboard, Edunext, ManageBac+, Toddle, OpenApply, Veracross, PowerSchool SIS); 2 Customers, users, commercial model; 3 Product boundaries and principles; 4 Configuration and tenancy model (platform → school group → legal entity → campus → programme/board → academic year → class/section → learner cohort/household); 5 Functional catalogue M01–M30; 6 Role-specific web and mobile; 7 End-to-end journeys (admission to first day, school-day safety, assessment to support, fee lifecycle, trip lifecycle); 8 Data model and interface contracts; 9 Integrations; 10 Security, privacy and child protection (DPDP Act 2023 and Rules 2025 need legal review); 11 NFRs (99.9% term-time availability, P95 < 2 s reads / < 4 s writes, pilot 5 campuses / 25,000 students, RPO 15 min / RTO 4 h, WCAG 2.2 AA); 12 Differentiation experiments; 13 Roadmap phases 0–4 with exit gates; 14 Acceptance scenarios; 15 Open decisions; 16 Sources.

Priorities: **P0** M01, M03, M04, M05, M06, M13, M14, M27, M29, M30. **P1** M02, M07–M12, M15–M19, M21, M24–M26. **P2** M20, M22, M23, M28.

## Acceptance scenarios (spec §14) and where they are covered

| Scenario | Stage A coverage |
|---|---|
| Guardian with two children on two campuses acts only for the correct child; separated guardian cannot see restricted data | **Covered at policy + API level**: `AccessPolicyTest.GuardianRelationships` (verified); `StageAApiIT` (written, not run). Pay/consent/message flows arrive with B8/Phase 2/B7 |
| Offline attendance with concurrent online correction, no silent overwrite | Not yet (B6, offline-first per D8) |
| Transport attendant boards wrong student → blocked or authorized override with trace | Not yet (Phase 2) |
| Duplicate and out-of-order payment events keep totals correct | Idempotency table verified (T9); gateway flow in B8 |
| Safeguarding case invisible to normal search; only its team; break-glass logged | **Covered at policy level**: `RestrictedCases` tests + DB break-glass constraints (verified). Case module is Phase 2 |
| Mid-year grade-rule change keeps old reports on old version | **Mechanism verified**: effective-dated config T6/T7 + `ConfigResolverTest`. Grading itself is Phase 3 |
| Tenant disables AI; everything still works | `flag.ai.enabled=false` seeded; no feature depends on AI |
| Multi-campus export leaks no other tenant's learner | **Verified at DB layer**: RLS T1–T4, T11–T12. Export/search/file URLs to be tested as they are built |
| New campus onboarded from CSV, reconciled, rolled back in staging | Not yet (B2, repeatable imports per D5) |

## Pilot decisions (answered 2026-09-25)

| # | Question | Decision | What it changes in the build |
|---|---|---|---|
| D1 | First buyer | **One premium school** | The tenant is the school (one legal entity, normally one campus). Multi-campus support stays in the model and tests, but the UI treats one campus as the default: the scope bar states the campus as text and hides the switcher. Onboarding is a single-school wizard, not a group rollout |
| D2 | Boards for release one | **CBSE and State board** | Both are programmes on the same campus (seeded). Classes, sections, subjects and grade scales are per programme, and grade scales are effective-dated config per programme. **Open: which state?** (drives the state grading scale, report formats, calendar and any state portal exports) |
| D3 | Sign-in | **Phone OTP for parents** | New slice B4 (right after student and guardian records, before notices and the parent app): OTP over SMS through a DLT-registered Indian provider; see ADR 0009. *Staff sign-in not specified; assumed: email/password with TOTP MFA for admin and finance roles via a self-hostable OIDC provider (Keycloak).* |
| D4 | Payments | **School is merchant of record; sandbox only** | Gateway adapter behind an interface; money settles to the school's account; Schoomano never holds funds. No live keys until contracted |
| D5 | Replace or coexist | **Run alongside for one term, using CSV exports** | Imports are **repeatable**, not one-off: each re-import is diffed against the last, unchanged rows are no-ops, conflicts are reported, and fields owned by the old system are read-only in Schoomano during coexistence. Direction is one-way (old system → Schoomano) |
| D6 | Hosting | **India region confirmed; provider not yet named** | Infrastructure stays cloud-neutral (OCI containers, managed PostgreSQL 16, S3-compatible storage). Provider choice is needed before the first shared environment |
| D7 | Privacy | **No real child data until the DPDP lawyer reviews** | Enforced by process (release checklist gate) and by design: seeds and fixtures are synthetic only |
| D8 | Devices | **Personal Android phones on patchy networks** | Teacher flows are offline-first with idempotent sync; OTP flow tolerates slow SMS (resend with cooldown); frontend JS budget ≤ 120 kB gzip for first load (currently ~90 kB); mobile prototype targets a 2 GB Android device |

## Remaining open question
Which **state board** does the pilot school follow? Needed before B1 finalises grade scales and before report-card work.

## Working assumptions (not blocking)

- English UI first, translation-ready; Hindi, Kannada and Tamil next, with native review.
- India Standard Time; the model carries a per-campus time zone anyway.
- Money stored as integer paise; GST handling decided with the finance owner before M14 invoices.
- Synthetic data only until a school-approved pilot environment exists.
- Mobile stack per ADR 0005 is proposed, to be confirmed by prototype.

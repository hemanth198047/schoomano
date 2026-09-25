# Specification baseline

Source: `School_Operations_Platform_Requirements_Specification.docx` (read in full on 2026-09-25) and the build prompt. Product name: **Schoomano**.

## Inventory

Sections read: Executive product decision; 1 Market comparison (Fedena, Entab CampusCare, MyClassboard, Edunext, ManageBac+, Toddle, OpenApply, Veracross, PowerSchool SIS); 2 Customers, users, commercial model; 3 Product boundaries and principles; 4 Configuration and tenancy model (platform → school group → legal entity → campus → programme/board → academic year → class/section → learner cohort/household); 5 Functional catalogue M01–M30; 6 Role-specific web and mobile; 7 End-to-end journeys (admission to first day, school-day safety, assessment to support, fee lifecycle, trip lifecycle); 8 Data model and interface contracts; 9 Integrations; 10 Security, privacy and child protection (DPDP Act 2023 and Rules 2025 need legal review); 11 NFRs (99.9% term-time availability, P95 < 2 s reads / < 4 s writes, pilot 5 campuses / 25,000 students, RPO 15 min / RTO 4 h, WCAG 2.2 AA); 12 Differentiation experiments; 13 Roadmap phases 0–4 with exit gates; 14 Acceptance scenarios; 15 Open decisions; 16 Sources.

Priorities: **P0** M01, M03, M04, M05, M06, M13, M14, M27, M29, M30. **P1** M02, M07–M12, M15–M19, M21, M24–M26. **P2** M20, M22, M23, M28.

## Acceptance scenarios (spec §14) and where they are covered

| Scenario | Stage A coverage |
|---|---|
| Guardian with two children on two campuses acts only for the correct child; separated guardian cannot see restricted data | **Covered at policy + API level**: `AccessPolicyTest.GuardianRelationships` (verified); `StageAApiIT` (written, not run). Pay/consent/message flows arrive with M14/M19/M13 |
| Offline attendance with concurrent online correction, no silent overwrite | Not yet (B5) |
| Transport attendant boards wrong student → blocked or authorized override with trace | Not yet (Phase 2) |
| Duplicate and out-of-order payment events keep totals correct | Idempotency table verified (T9); gateway flow in B7 |
| Safeguarding case invisible to normal search; only its team; break-glass logged | **Covered at policy level**: `RestrictedCases` tests + DB break-glass constraints (verified). Case module is Phase 2 |
| Mid-year grade-rule change keeps old reports on old version | **Mechanism verified**: effective-dated config T6/T7 + `ConfigResolverTest`. Grading itself is Phase 3 |
| Tenant disables AI; everything still works | `flag.ai.enabled=false` seeded; no feature depends on AI |
| Multi-campus export leaks no other tenant's learner | **Verified at DB layer**: RLS T1–T4, T11–T12. Export/search/file URLs to be tested as they are built |
| New campus onboarded from CSV, reconciled, rolled back in staging | Not yet (B2) |

## Blocking decisions (need answers from pilot owner)

1. **First buyer (D1)**: premium single-campus school or a multi-campus group? This changes onboarding, the scope switcher emphasis and pricing. *Assumed: a multi-campus group with 2–5 campuses in Bengaluru-type urban India.*
2. **Boards and grade scales for release one**: CBSE only, or CBSE + ICSE/IB/Cambridge? Which report-card formats? *Assumed: CBSE first, IB second; grade scales configurable, report cards Phase 3.*
3. **Identity provider (D3)**: school Google Workspace / Microsoft 365, or our own Keycloak? How do guardians sign in (OTP to mobile is the norm)? *Assumed: Keycloak (self-hostable) with SMS OTP for guardians via an Indian SMS provider under DLT registration.*
4. **Payment gateway and merchant of record**: Razorpay, PayU, Cashfree or the school's bank; does money settle to each legal entity directly? *Assumed: school entity is merchant of record; we integrate one gateway first, sandbox only until contracted.*
5. **System of record vs coexistence**: replace the current SIS/fees system at pilot, or run alongside with sync? Which exports can the current vendor provide? *Assumed: coexistence for one term, then cutover; CSV/Excel exports are the only reliable source.*
6. **Hosting**: which cloud and India region; any school requirement for data residency beyond DPDP? *Assumed: a single India region with encrypted backups in a second Indian region.*
7. **Privacy owner and legal review**: who at the school owns retention, consent and guardian-relationship policies, and who is our DPDP counsel? *Assumed: nothing goes to real student data until counsel reviews the consent flows and a DPA is signed.*
8. **Devices and contracted operations**: do teachers and transport attendants have personal phones or shared school devices; are transport, meals and security outsourced? *Assumed: teachers use personal Android phones on unreliable networks; transport is contracted with school-side attendants.*

## Working assumptions (not blocking)

- English UI first, translation-ready; Hindi, Kannada and Tamil next, with native review.
- India Standard Time for all campuses in the pilot; the model carries a per-campus time zone anyway.
- Money stored as integer paise; GST handling decided with the finance owner before M14 invoices.
- Synthetic data only until a school-approved pilot environment exists.
- Mobile stack per ADR 0005 is proposed, to be confirmed by prototype.

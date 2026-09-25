# Backlog (dependency-ordered vertical slices)

Each slice ships database → API → web/mobile → tests → docs, and updates `IMPLEMENTATION_STATUS.md`. "Done" means the acceptance checks below pass in CI.

## Stage A: Foundation (this delivery)
Tenancy + RLS, identity/roles/relationships model, authorization policy, effective-dated config and flags, audit + outbox + idempotency tables, admin web shell and design system, CI, ADRs. See `IMPLEMENTATION_STATUS.md`.

## Stage A.1: First green local build (next, small)
- `mvn verify` green locally including `StageAApiIT` with Docker; fix anything it reveals.
- Upgrade Spring Boot 3.5.6 → 4.0.x (ADR 0001) and re-run.
- Push to GitHub; CI green on all four jobs.
- **Acceptance**: all four CI jobs green on `main`; README commands verified on one Windows and one Linux machine.

## Pilot slices (Phase 1 "sellable core")

| Slice | Modules | Depends on | Acceptance (measurable) |
|---|---|---|---|
| **B1** Academic structure | M04 | A.1 | Admin creates programmes, grades, sections, terms and a campus calendar; year rollover preview shows promotions without writing; overlapping terms rejected; campus switcher filters lists; all actions audited |
| **B2** Import foundation | M29, M27 | B1 | CSV upload → validation report with row-level errors → dry run with counts → idempotent commit (re-upload changes nothing) → rollback of a batch in staging. 5,000 rows import < 60 s |
| **B3** Student and guardian records | M03, M01 | B1, B2 | Student profile with stable school ref; households and guardian relationships with capabilities editable by authorised staff, each change audited with reason; separated-guardian restriction enforced end to end; documents deferred to object-storage sub-slice |
| **B4** Timetable | M05 | B1, B3 | Build and publish a weekly timetable; teacher/room/section clashes block publish with a clear list; substitutions for an absent teacher with notification via outbox |
| **B5** Attendance | M06 | B4 | Teacher marks session attendance on phone (web PWA first); offline queue with idempotency keys; concurrent correction produces a visible conflict, history preserved (spec §14 scenario 2); late threshold uses effective-dated config; guardian notified of absence within 5 min |
| **B6** Notices and messaging | M13 | B3 | Targeted notices (campus/class/household) with delivery status; guardians see only their children's notices; restricted guardians receive notices; quiet hours respected |
| **B7** Fees | M14 | B3 | Fee plans, invoices in paise, sandbox gateway; duplicate and out-of-order webhooks keep ledger and receipts correct (spec §14 scenario 4); reconciliation report |
| **B8** Mobile apps | M13, M06, M14 | B5–B7, ADR 0005 prototype | Guardian: child switcher, attendance, notices, pay. Teacher: offline attendance. Cold start < 3 s on a 2 GB Android device |
| **B9** OIDC + MFA + support console | M01, M30 | D3 answered | Real IdP sign-in; MFA for admin roles; tenant-approved support session with expiry, visible to the school, fully audited |

**Phase 1 exit gate (spec §13)**: one campus completes a term-critical daily cycle with reconciled data.

## Later phases
- **Phase 2**: M02 admissions, M17 transport (wrong-student boarding scenario), M18, M19 trips with consent, M11 safeguarding cases, M12 health, M16 HR, approvals, M25 analytics.
- **Phase 3**: M07, M08, M09 report cards on effective-dated grade rules, M10 interventions.
- **Phase 4**: M20, M21, M22, M23, M26 studio, OneRoster/LTI, M28 AI, group benchmarking.

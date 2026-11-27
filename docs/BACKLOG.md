# Backlog (dependency-ordered vertical slices)

Each slice ships database → API → web (and mobile where stated) → tests → docs, and updates `IMPLEMENTATION_STATUS.md`. "Done" means the acceptance checks pass in CI. Ordering reflects the pilot decisions in `SPEC_BASELINE.md` (one premium school, CBSE + State board, parent phone OTP, one term of coexistence with the old system, personal Android phones on patchy networks).

## Stage A: Foundation (delivered)
Tenancy + RLS, identity/roles/relationships model, authorization policy, effective-dated config and flags, audit + outbox + idempotency tables, admin web shell and design system, separated frontend/backend, container images, start/stop scripts for Unix-like systems and Windows, CI.

## Stage A.1: First green build (in progress)
**Done 2026-11-10:** `mvn verify` green (needed Testcontainers 1.21.4 for Docker Engine 29); `--dev` stack verified end to end on Linux. CI run #2 on `main`: all 6 jobs green, both images build. **Left:** Spring Boot 4 upgrade, Windows and macOS runs.

- `scripts/start` brings the full stack up on a developer machine (this is also the first real compile of the Spring layer), and `cd backend && mvn verify` passes including `StageAApiIT`. Fix whatever surfaces.
- Upgrade Spring Boot 3.5.6 → 4.0.x (ADR 0001) and re-run.
- Push to GitHub; all six CI jobs green.
- **Acceptance**: CI green on `main`; `scripts/start` + sign-in verified on one Windows 11 and one macOS or Linux machine.

## Pilot slices (Phase 1 "sellable core")

| Slice | Modules | Depends on | Acceptance (measurable) |
|---|---|---|---|
| **B1** Academic structure *(done 2026-11-10)* | M04 | A.1 | Per programme (CBSE, State): grades, sections, subjects, terms; campus calendar with holidays; grade scales as effective-dated config per programme; year-rollover preview writes nothing; overlapping terms rejected; all changes audited |
| **B2** Repeatable import (coexistence) *(done 2026-11-10 for students; guardians follow B3)* | M29, M27 | B1 | Upload CSV/Excel export → row-level validation report → dry-run with counts → commit. Re-importing the same file changes nothing; a changed file applies only the differences; conflicts with Schoomano-side edits are listed, never silently overwritten; a batch can be rolled back. Fields owned by the old system are read-only in the UI during coexistence. 5,000 students import in < 60 s |
| **B3** Students and guardians *(done 2026-11-10)* | M03, M01 | B1, B2 | Student profile with stable school ref; households; guardian relationships with capabilities, editable by authorised staff with reason, audited; separated-guardian restriction enforced end to end; guardian mobile numbers verified format (+91) and unique per guardian |
| **B4** Sign-in *(parent phone OTP built 2026-11-18: database and web verified; API awaiting its first compile in CI; staff OIDC provider setup, real SMS provider and the office session-revocation screen remain)* | M01 | B3 | **Parents:** phone OTP (ADR 0009): 6 digits, 5-minute expiry, hashed at rest, 5 attempts then lock, resend after 30 s, per-number and per-IP limits, identical response whether or not the number is known; works on a throttled 2G profile. **Staff:** OIDC with TOTP MFA for admin/finance roles. Session revocation from the admin UI |
| **B5** Timetable (done, verified in CI) | M05 | B1, B3 | Weekly timetable per section; teacher/room/section clashes block publish with a list; substitutions for absent teachers, notified via outbox |
| **B6** Attendance (offline-first; server and web done and verified in CI, Android in B9) | M06 | B5, B4 | Teacher marks attendance on a personal Android phone with the network off; queued with idempotency keys; syncs when online; a concurrent office correction produces a visible conflict with history kept (spec §14); late threshold from config; guardian absence notice within 5 minutes of sync |
| **B7** (done, verified in CI) Notices and messaging | M13 | B3, B4 | Targeted notices (school/programme/class/household) with delivery status; restricted guardians receive notices only; quiet hours; SMS fallback for parents without the app |
| **B8** (done, verified in CI) Fees | M14 | B3 | Fee plans per programme; invoices in paise; sandbox gateway with the school as merchant of record; duplicate and out-of-order webhooks keep ledger and receipts correct (spec §14); daily reconciliation report |
| **B9** (in progress: server and app code done, device testing and staff sign-in outstanding) Mobile apps | M13, M06, M14 | B4–B8, ADR 0005 prototype | Parent: OTP sign-in, child switcher, attendance, notices, pay. Teacher: offline attendance. Cold start < 3 s on a 2 GB Android device; works through 30-minute offline periods |
| **B10** (done, verified in CI) Support console | M30 | B4 | School-approved, time-limited support sessions visible to the school; full audit; health dashboard |
| **B11** (done, verified in CI) Assessment and report cards | M09 | B4 | Teacher enters marks; locked marks change only with a reason and history; report cards published as frozen snapshots on the grade scale in force; corrections are new revisions; guardians read their child's card |
| **B12** (done, verified in CI) Admissions | M02 | B4 | Enquiry to enrolment with fixed stages, reasons to end, seat checks with override, one live application per child, history; enrolment creates student, guardian and placement |
| **B13** (done, verified in CI) Curriculum and learning | M07, M08 | B4 | Syllabus per grade and subject, permanent coverage per class, homework visible to guardians with view-academic |
| **B14** (done, verified in CI) Student support | M10 | B4 | Confidential cases for enrolled children, permanent notes, review dates, closure with a reason, reads audited; principal and administrators only |
| **B15** (done, verified in CI) Safeguarding | M11 | B4 | Staff report concerns and get a receipt only; the designated lead reads, triages, escalates and closes through fixed steps with notes; overdue triage flagged; reads audited |
| **B16** (done, verified in CI) Health | M12 | B4 | Nurse-only health record and infirmary log in the restricted health partition; teachers and administrators see only a one-line emergency flag; reads and saves audited |
| **B17** (done, verified in CI) Finance and procurement | M15 | B4 | Budgets per campus, year and category; purchase requests approved by someone else within budget (principal above 50,000 rupees); vendor payments recorded once; history permanent |
| **B18** (done, verified in CI) HR and workforce | M16 | B4 | Staff profiles, yearly leave allowances, leave requests approved by someone else within balance; decisions final |
| **B19** (done, verified in CI) Transport | M17 | B4 | Vehicles, routes with stops, student assignments within vehicle capacity, boarding records, guardian view of own child |
| **B20** (done, verified in CI) Co-curricular | M18 | B4 | Activities with seat limits, student membership, session attendance, guardian view of own child |
| **B21** (done, verified in CI) Trips | M19 | B4 | Trips with a consent deadline, guardian consent per child, invited list, day-of headcount |
| **B22** (done, verified in CI) Facilities | M21 | B4 | Room bookings without clashes and maintenance requests; visitors still to do |
| **B23** (done, CI pending) Compliance register | M24 | B4 | Obligations with owners and due dates, permanent evidence that moves the due date; no legal content |

**Phase 1 exit gate (spec §13)**: the pilot school completes a term-critical daily cycle with reconciled data while running alongside its current system.

## Later phases
- **Phase 2**: M02 admissions, M17 transport (wrong-student boarding scenario), M18, M19 trips with consent, M11 safeguarding cases, M12 health, M16 HR, approvals, M25 analytics.
- **Phase 3**: M07, M08, M09 report cards for CBSE and the chosen State board on effective-dated grade rules, M10 interventions.
- **Phase 4**: M20, M21, M22, M23, M26 studio, OneRoster/LTI, M28 AI, multi-campus group features.

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
| **B4** Sign-in *(parent phone OTP verified in CI; staff OIDC provider setup, a real SMS provider and the office session-revocation screen remain)* | M01 | B3 | **Parents:** phone OTP (ADR 0009): 6 digits, 5-minute expiry, hashed at rest, 5 attempts then lock, resend after 30 s, per-number and per-IP limits, identical response whether or not the number is known; works on a throttled 2G profile. **Staff:** OIDC with TOTP MFA for admin/finance roles. Session revocation from the admin UI |
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
| **B23** (done, verified in CI) Compliance register | M24 | B4 | Obligations with owners and due dates, permanent evidence that moves the due date; no legal content |
| **B24** (done, verified in CI) Analytics | M25 | B4 | Read-only campus summary of enrolment, attendance and fees; counts only |
| **B25** (done, verified in CI) Workflow | M26 | B4 | Versioned approval chains and step-by-step requests with a permanent decision record |
| **B26** (done, verified in CI) Purchase approvals via chains | M26, M15 | B25 | A purchase request can be routed through a published chain and is then decided only there |
| **B27** (done, verified in CI) Leave approvals via chains | M26, M16 | B25 | A leave request can be routed through a published chain and is then decided only there |
| **B28** (done, verified in CI) Visitor log | M21 | B4 | Gate check-in and check-out with host and badge; permanent entries |
| **B29** (done, verified in CI) Front-desk role | M21 | B28 | A gate-only role that records visitors without campus-admin access |
| **B30** (done, verified in CI) Approval notices | M26 | B25 | In-app notices to approvers when a request reaches them and to the requester when it is decided |
| **B31** (done, verified in CI) Visitor arrival notice | M21 | B28, B30 | The staff member being visited gets an in-app notice when their visitor is checked in |
| **B32** (done, verified in CI) Pre-registered visitors | M21 | B28, B31 | Staff announce guests ahead; the gate checks them in against the announcement |
| **B33** (done, verified in CI) API keys | M27 | B1 | Read-only, expiring, revocable keys for outside systems, shown once |
| **B34** (done, verified in CI) Webhooks | M27 | B33 | Signed, retried delivery of school events to registered https endpoints; no student events until D7 |
| **B35** (done, verified in CI) Approval reminders and escalation | M26 | B25, B30 | A request waiting 24 hours reminds the step's holders; at 72 hours leadership and the requester are told |
| **B36** (done, verified in CI) Idempotency keys | M27, B6 | B33 | A repeated write with the same Idempotency-Key returns the first answer and does the work once |
| **B63** (verified in CI) Late threshold applied automatically | M06 | B6 | A PRESENT mark made for today after the day start (`attendance.day_start`, set per campus or group) plus `attendance.late_after_minutes` is recorded as LATE; earlier days and unset configuration are left alone. Unit tests; integration test. |
| **B62** (verified in CI) Class teachers | M06 | B6 | Who is class teacher of a section (teacher role required, ended with a reason, never deleted); where a section has one, only its class teachers may mark attendance, sections without one work as before. Database guard test with mutation check; integration test. Web screen and the same rule for entering marks are next. |
| **B61** (verified in CI) Staff and access | M01 | B3 | Group administrators add staff and give or end roles (group or campus scope, reason kept, last administrator protected); web page; integration test. Invitation acceptance (linking a sign-in to the person) waits for a real OIDC provider. |
| **B60** (verified in CI) Parents see and end their own devices | M01 | B4 | Three auth endpoints; mobile section; integration and mobile tests |
| **B59** (done, verified in CI) Student documents | M03 | B3 | Upload, download, remove with a reason; checked by bytes; audited; SQL, unit, integration and web tests |
| **B58** (done, verified in CI) Office screen for guardian sessions | M01 | B4 | Devices list and sign out everywhere on the student profile; web test |
| **B57** (done, verified in CI) Pilot readiness list and audit retention proposal | M30 | D7 | Two documents; nothing switched on |
| **B56** (done, verified in CI) Approvals on the phone | M26 | B25 | Approver mode in the mobile app: inbox across campuses, approve or reject; core tests |
| **B55** (done, verified in CI) Approvers must hold the role for the campus | M26 | B25 | Trigger on decisions; SQL test mutation-checked |
| **B54** (done, verified in CI) Tell the school when support first signs in | M30 | B10, B45 | first_used_at on the grant; Security notice to principals and group admins; integration test |
| **B53** (done, verified in CI) Relationship column in the student import | M29 | B38 | Grandparent, other, mother, father, guardian; unit tests |
| **B52** (done, verified in CI) Check an audit export on its own | M30 | B43 | Export carries what each hash covers; Node verifier; fixture tests; CI end-to-end |
| **B50** (done, verified in CI) Households for imported students | M29 | B38 | Siblings share a household; rollback removes what was added; integration test |
| **B49** (done, verified in CI) Change the encryption key | M27 | B39 | Key labels, previous keys, re-seal at start-up, safe to repeat; tests |
| **B48** (done, verified in CI) Emergency access from the Health page | M30 | B44 | Search, reason, duration, then read-only record; web tests |
| **B47** (done, verified in CI) Backup and restore drill | M30 | B1 | Script that restores a dump to a scratch database and proves it; runbook; CI with damage checks |
| **B46** (done, verified in CI) Alerting on operational checks | M30 | B10, B45 | Administrators told once per problem, reminded daily, shared checks with the health screen |
| **B45** (done, verified in CI) Security notices for emergency access | M30 | B44, B30 | Reviewers told when emergency access is used; the person told when a review ends it |
| **B44** (done, verified in CI) Break-glass request and review | M30 | B15, B16 | Ask with a reason, short expiry, reviewed by someone else, bad review ends access |
| **B43** (done, verified in CI) Audit export | M30 | B10 | Date-range CSV with hashes, formula-safe, itself audited |
| **B42** (done, verified in CI) Old-system fields shown read-only | M29 | B3 | Every owned detail on the student profile carries an Old system badge |
| **B41** (done, verified in CI) Import purges in the audit log | M29 | B37 | Each purge and stale discard is a SYSTEM audit event without student data |
| **B40** (done, verified in CI) Upload idempotency and required key | M27 | B36, B39 | The import upload honours Idempotency-Key; prod will not start without the secrets key |
| **B39** (done, verified in CI) Webhook secret encryption and rotation | M27 | B34 | Secrets encrypted at rest; replace a secret with an overlap period when posts carry both signatures |
| **B37** (done, verified in CI) Import file purge | M29 | B2 | Settled imports lose their file and row values after retention and keep their record |
| **B38** (done, verified in CI) Guardians in the student import | M29 | B2, B3 | Father, mother and guardian columns link or create contacts; never revive an ended link; rolled back with the import |

**Phase 1 exit gate (spec §13)**: the pilot school completes a term-critical daily cycle with reconciled data while running alongside its current system.

## Later phases
- **Remaining from the original phases**: M20 library, M22 food and nutrition, M23 hostel and M28 AI assistance (all P2, deferred); OneRoster and LTI integration; multi-campus group features beyond what exists.
- **Remaining work inside started modules** is listed in "What is left before the application can be called complete" in `IMPLEMENTATION_STATUS.md`.

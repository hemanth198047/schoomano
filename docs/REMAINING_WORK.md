# Remaining work that is code only

Everything left that can be finished by building, in the order to do it. Items that need an outside party are at the end and are
not part of this list. Size: S under a day, M a few days, L about a week or more. Each item follows the usual slice: database, API,
web (mobile where useful), tests, docs, CI. The detail for each module is in its "Missing" list in `IMPLEMENTATION_STATUS.md`.

## Wave A. What a pilot school asks for in the first month
1. **M01 office screens** (M): ~~end a person's sessions~~ (done, B58), parents' own session list, invitations, custom roles screen.
2. **M03 student records** (M): sibling and household screens, merge of duplicate students, ~~documents~~ (done, B59).
3. **File attachments, once** (L): student documents (B59) are the first user, with the upload rules in `UploadRules` and files stored in the database beside the record; the other modules still need their own tables and screens on the same rules. One upload service (size and type limits, virus-scan hook, per-school storage, access through the same policy as the record). Needed by M03, M07, M08, M10, M11, M15, M16, M24, M26. Do this before the modules that use it.
4. **M06 attendance** (M): class-teacher assignment, per-period attendance, the late threshold applied automatically, guardian view of attendance, reports.
5. **M09 report cards** (L): term weighting, comments and co-scholastic grades, rank and class average, PDF report cards, marks import, campus override of the grade scale, guardian report cards on web, teacher-to-class assignment.
6. **M14 fees** (L): fee plans per programme, concessions, instalments, late fees, refunds and credit notes, PDF receipts, single-invoice issue on web, fee head screen, ledger export, expiry job for stale pending payments. (The payment gateway itself is outside.)
7. **M13 notices** (M): target by programme or household, SMS only for parents without the app, scheduled sends, attachments, templates, read receipts for staff, quiet hours as a school setting, consent preferences per parent. (Real SMS and push delivery are outside.)
8. **M12 health** (M): medication schedules and consent, vaccination and screening records, parent-supplied forms, notify guardians from a visit, guardian view of own child, trip and transport flag views.

## Wave B. Running the school day to day
9. **M05 timetable** (M): room clash screen, bulk timetable import, period swaps, Saturday-only schedules.
10. **M16 HR** (L): half-day leave, holiday calendar in the day count, carry-forward, leave linked to substitutions, staff attendance, contracts and documents, exit workflow, edit after leaving; payroll records (L, last).
11. **M15 finance** (L): purchase orders and goods received, quotes and comparison, partial payments, ledger and chart of accounts, bank reconciliation, budget carry-over, group consolidated reports, vendor edit screen.
12. **M17 transport** (M): guardian boarding notices (events already emitted), edit routes and vehicles, reorder stops, fees linkage, authorised pick-up adults, incident log, driver and vehicle documents. (GPS tracking needs hardware, outside.)
13. **M18 co-curricular** (M): fixtures, house points, achievements and certificates, per-activity leaders, waiting lists, activity fees, parent consent, edit activity, reports.
14. **M19 trips** (M): invoice the trip fee, signed consent and medical declaration, risk assessment, staff ratio, notices when consent is missing, emergency contacts on the day, reports.
15. **M21 facilities and visitors** (S): fill the gaps in its Missing list.
16. **M24 compliance** (M): evidence attachments, reminders and escalation, group view, owner as a linked person, evidence pack export, mobile screen. (Retention schedules and data-subject-request workflows wait on D7.)

## Wave C. Teaching and learning
17. **M07 curriculum** (M): lesson plans, schemes of work, resources, board mapping, edit and reorder units, copy a syllabus to the next year.
18. **M08 learning delivery** (L): homework submission and marking, resources, lesson log linked to the timetable, teacher-to-class assignment, parent acknowledgement.
19. **M10 student support** (M): counsellor role, teachers raising a concern, referrals, sharing with guardians, support plans with goals, overdue-review alerts, break-glass for outside roles.
20. **M11 safeguarding** (M): case team assignment, break-glass screen and emergency-access request from the screen, external referrals, rules for telling guardians, other incident types. (Retention and deletion rules wait on D7.)
21. **M02 admissions** (L): public enquiry form, follow-up tasks, entrance tests and interviews, documents, application fees, waitlist ordering, sibling and staff-ward rules, source and conversion reports, offer by message.

## Wave D. Platform and decisions
22. **M26 workflow** (L): routing by amount or condition, parallel and any-of approvals, delegation, repeating reminders, escalation to a named person, more kinds of linked request, triggers and automations (last).
23. **M25 analytics** (M): assessment, homework, admissions, staff and leave figures, trends, campus comparison, exports, saved reports, threshold alerts, hiding small groups, mobile screen.
24. **M27 integration** (M): close the DNS rebinding gap in the webhook client, replay of old deliveries, per-key scope, rate limits, usage log, move older idempotency keys onto the common filter, campus-specific endpoints.
25. **M30 operations** (M): support-staff identity management, a platform-side console for support, dashboards and metrics, the alert channels' code (sending waits on providers).
26. **Mobile staff screens** (L): the web-only staff features that make sense on a phone, chosen after Wave A.
27. **Spring Boot 3.5 to 4.0** (L): after all feature work, so the upgrade is done once.

## Not on this list (needs an outside party)
Real SMS provider and DLT; staff sign-in provider; payment gateway and merchant onboarding; push notification accounts; GPS hardware;
the lawyer's review (D7) and everything that depends on it (audit, safeguarding and compliance retention, data-subject requests);
real phone, Windows and macOS runs; native review of Hindi and State-language text; the pilot school. Deferred by decision:
M20 library, M22 food, M23 hostel, M28 AI.

# Pilot readiness

What the code can do is listed in `IMPLEMENTATION_STATUS.md`. This page is only what has to happen outside the code before a
school runs on it. Start the items with a long wait first. Nothing here can be done by the build.

| # | Item | Wait | Who | What to prepare | Done when |
|---|------|------|-----|-----------------|-----------|
| 1 | Lawyer's review under the DPDP Act (D7) | weeks | School group's counsel | The list of what is stored about children, guardians, staff and visitors (`IMPLEMENTATION_STATUS.md`, one row per module); the retention questions in `AUDIT_RETENTION.md`; the consent flows (guardian notices, trip consent, health record) | A written opinion and the retention periods chosen; until then only made-up data goes in |
| 2 | SMS sender and templates (TRAI DLT) | weeks | School group | Entity registration, a sender id, one approved template each for the sign-in code and for notices | A real code reaches a real phone; the console sender is switched off in production |
| 3 | Staff sign-in provider (OIDC) | days | School group IT | A provider that reports the second factor (`amr`), a client for the app, the issuer address | A principal signs in with a second factor; a campus administrator without one is refused |
| 4 | Production secrets | hours | Whoever deploys | `SCHOOMANO_SECRETS_KEY` (32 bytes, base64) in a secret manager and saved separately from database backups; database passwords; the OIDC settings | The prod profile starts, and refuses to start without the key |
| 5 | Backup schedule and restore drill | days | Whoever deploys | Decide how often backups run, how much loss is acceptable and how long a restore may take (`BACKUP_RESTORE.md`) | A scheduled backup and one successful drill against a copy of the real database |
| 6 | A real phone | days | A person with a low-end Android phone | The mobile build; a list of the checks in ADR 0005 (cold start, offline period, push) | The checks are written down with results; ADR 0005 moves from Proposed |
| 7 | Windows and macOS runs | hours | Anyone with those machines | `scripts/start.*` and `scripts/db-test.*` | Both run clean once |
| 8 | A pilot school | weeks | School group | A campus willing to run beside its current system; an export of its student list in the import format | Phase 1 exit gate (spec section 13) |

Order that costs least: ask counsel and the SMS registration to start the same day; set up the staff sign-in and secrets while
they run; do the backup drill on the first real database copy; then bring in the pilot campus with its student file, using the
import dry run and keeping the rollback window (90 days) in mind.

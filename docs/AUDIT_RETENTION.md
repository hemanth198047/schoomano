# Audit retention and archiving: proposal, not built

Status: waiting for the legal review (D7). Nothing here is switched on. The audit trail is append-only and hash-chained per
school, so trimming it is not a small change and should not be made before the periods are known.

## What the lawyer has to decide

1. How long audit entries about children's records, money and access must be kept, and whether the periods differ by kind
   (access to health or safeguarding records, fee changes, sign-ins, support access).
2. Whether entries may be deleted after that, or must be moved to cheaper storage and kept.
3. Who may read an archive and for what.

## Proposed design (once the periods are known)

- A checkpoint table per school: the last archived sequence number and its hash. `audit_verify` starts from the checkpoint
  instead of expecting sequence 1, so the chain stays checkable after older entries are removed.
- Archive in two steps, never one: (a) export the period with the audit export (it already carries what each hash covers, and
  `backend/scripts/verify-audit-export.mjs` checks it on its own); store the file with the backups, with the same key rules;
  (b) only after the file has been checked and its hash written down, a function owned by the schema owner (not the application
  role) removes the entries up to the checkpoint and writes the new checkpoint. Both steps are audited as system actions.
- Never remove entries that are still needed by an open emergency-access review or a support grant.
- A setting for the period per kind of entry, with the default being keep everything.
- A health check that warns when a school has entries older than its period that have not been archived.

## What exists today that this builds on

Export with hashes (B43, B52), the stand-alone verifier (B52), per-school chain verification (V12), the backup drill (B47).

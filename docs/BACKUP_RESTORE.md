# Backup and restore

What a school's data is made of, how to back it up, how to prove the backup works, and how to get back from one.
The drill script is real and runs in CI on every push. The operating decisions at the bottom are not made yet.

## What has to be backed up

| What | Where it lives | Notes |
|---|---|---|
| All school data | The PostgreSQL database (every school in one database, separated by row-level security) | Includes the audit trail, imports, notices, approvals. A full dump (`pg_dump -Fc`) or the cloud provider's snapshots both work. |
| The encryption key | `schoomano.secrets.key` (environment `SCHOOMANO_SECRETS_KEY`) | Webhook signing secrets in the database are encrypted with it. A backup of the database without the key cannot sign webhooks again. Keep the key in the secret manager and back it up on its own, never in the same place as the database dump. |
| Settings and sign-in provider details | Environment variables / the secret manager | See `.env.example` and the release checklist. |
| Files | Nothing. Imports keep their parsed rows in the database and are blanked after retention. | There is no file store to back up yet. |

## The drill

`backend/scripts/backup-drill.sh [database]` takes a full dump, restores it into a scratch database on the same server,
and checks the copy: same rows in every table, every school's audit trail still verifies with the same entry count,
row-level security still on and forced on the same tables and still hiding other schools from the application role,
and the same grants, functions and triggers. It prints the newest audit entry in the backup, which is the most a restore
from that backup could lose, then drops the scratch database. It exits 1 if anything differs.

```sh
PGHOST=db.example PGUSER=schoomano_owner PGPASSWORD=... backend/scripts/backup-drill.sh schoomano
```

Run it against a quiet database or a replica, because rows written while it runs make the counts differ. The scratch
copy holds real data while it exists, so run it where the production database may be read, and let it clean up.

`DRILL_TAMPER=rows|rls|audit` damages the copy on purpose, and the drill must then fail. CI runs all three after the
normal drill, so a drill that stopped noticing problems would fail the build.

## Getting back from a backup

1. Start a PostgreSQL 16 server. Create the two login roles first (`schoomano_owner` as the owner, `schoomano_app` without
   superuser or bypass of row-level security) with the same names, because the dump refers to them.
2. `createdb schoomano` as the owner, then `pg_restore --no-owner --exit-on-error -d schoomano backup.dump`.
3. Run `backend/scripts/backup-drill.sh schoomano` against the restored database. It restores a copy of the copy, which
   proves the restored database is itself backed up and consistent.
4. Put the encryption key back in the application's environment, then start the application. The migrations that are
   already recorded are skipped.
5. In the app, open Support and audit and use "Check the trail has not been altered" for each school.

## Not decided yet (these need the school or operator)

- How often backups are taken and how much loss is acceptable (recovery point). The drill reports the newest entry; the
  number to aim for is a decision.
- How long a restore may take (recovery time), and who is on call to do it.
- Where backups are stored, who can read them (they hold every child's records), and when they are deleted. This falls under
  the data-protection review (decision D7).
- How often the drill is run against production. Monthly is a reasonable start; record each run.
- A restore into a different region or cloud, and a restore of one school only (the dump restores all schools together).

## Changing the encryption key

1. Make a new 32-byte key (`openssl rand -base64 32`) and store it in the secret manager.
2. Set `SCHOOMANO_SECRETS_KEY` to the new key and `SCHOOMANO_SECRETS_PREVIOUS_KEYS` to the old one (several old keys are separated by commas), and `SCHOOMANO_SECRETS_REKEY=true`.
3. Restart. The log says how many secrets were re-sealed. It is safe to run again and from several instances.
4. Remove the old key and `SCHOOMANO_SECRETS_REKEY`, restart. Keep the old key in the secret manager until every backup older than step 3 has expired, because those backups can only be read with it.


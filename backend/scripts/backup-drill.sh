#!/bin/sh
# Backup and restore drill. Takes a full dump of the school database, restores it into a scratch database on the same
# server, and proves the copy is usable: every table has the same rows, every school's audit trail still verifies,
# row-level security is still switched on and still hides other schools from the application role, privileges and
# functions came across, and the newest record in the copy is reported so you can see how much a restore would lose.
# The scratch database is dropped at the end. Exits 1 if any check fails.
#
# Usage (libpq variables choose the server and the owner login):
#   PGHOST=localhost PGUSER=schoomano_owner PGPASSWORD=... backend/scripts/backup-drill.sh [database]
# Set DRILL_TAMPER=rows, rls or audit to damage the copy on purpose; the drill must then fail (a check on the check).
# Run it against a quiet database or a replica: rows written while it runs make the counts differ.
set -eu

SRC="${1:-${PGDATABASE:-schoomano}}"
APP_ROLE="${SCHOOMANO_APP_ROLE:-schoomano_app}"
COPY="${SRC}_drill_$$"
WORK="$(mktemp -d)"
FAILED=0
START="$(date +%s)"

q() { psql -X -q -At -v ON_ERROR_STOP=1 -d "$1" -c "$2"; }
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
cleanup() {
  psql -X -q -d postgres -c "drop database if exists \"$COPY\"" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

printf 'Backup drill for "%s" (scratch copy "%s")\n' "$SRC" "$COPY"

pg_dump -Fc -d "$SRC" -f "$WORK/backup.dump"
SIZE="$(wc -c <"$WORK/backup.dump" | tr -d ' ')"
pass "dump taken ($SIZE bytes)"

psql -X -q -d postgres -v ON_ERROR_STOP=1 -c "create database \"$COPY\""
pg_restore --no-owner -d "$COPY" --exit-on-error "$WORK/backup.dump"
pass "restored into a scratch database"

case "${DRILL_TAMPER:-}" in
  rows) q "$COPY" "delete from role_grant where ctid in (select ctid from role_grant limit 1)" ;;
  rls) q "$COPY" "alter table person no force row level security" ;;
  audit) q "$COPY" "alter table audit_event disable trigger user; update audit_event set action = 'tampered' where seq = 2 and tenant_id = (select id from tenant order by created_at limit 1)" ;;
  "") ;;
  *) printf 'unknown DRILL_TAMPER value\n' >&2; exit 2 ;;
esac

# 1. Every table has exactly the rows it had.
COUNTS="select table_name || ':' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
          from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1"
q "$SRC" "$COUNTS" >"$WORK/rows.src"
q "$COPY" "$COUNTS" >"$WORK/rows.copy"
TABLES="$(wc -l <"$WORK/rows.src" | tr -d ' ')"
if diff "$WORK/rows.src" "$WORK/rows.copy" >"$WORK/rows.diff"; then
  pass "row counts match in all $TABLES tables"
else
  fail "row counts differ: $(tr '\n' ' ' <"$WORK/rows.diff" | cut -c1-300)"
fi

# 2. Every school's audit trail still verifies, with the same number of entries.
AUDIT="select string_agg(t.slug || '=' || v.checked || '/' || coalesce(v.broken_seq::text, 'ok'), ' ' order by t.slug)
         from tenant t cross join lateral audit_verify(t.id) v"
A_SRC="$(q "$SRC" "$AUDIT")"
A_COPY="$(q "$COPY" "$AUDIT")"
case "$A_COPY" in
  *=*/[0-9]*) fail "audit trail does not verify in the copy: $A_COPY" ;;
  *) if [ "$A_SRC" = "$A_COPY" ]; then pass "audit trails verify and match ($A_COPY)"; else fail "audit trails differ: source $A_SRC, copy $A_COPY"; fi ;;
esac

# 3. Row-level security is still on, still forced, and still hides other schools from the application role.
RLS="select count(*) filter (where relrowsecurity) || '/' || count(*) filter (where relforcerowsecurity)
       from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r'"
R_SRC="$(q "$SRC" "$RLS")"
R_COPY="$(q "$COPY" "$RLS")"
if [ "$R_SRC" = "$R_COPY" ]; then pass "row-level security on and forced on the same tables ($R_COPY)"; else fail "row-level security differs: source $R_SRC, copy $R_COPY"; fi

TENANT="$(q "$COPY" "select id from tenant order by created_at limit 1")"
SEES="$(psql -X -q -At -v ON_ERROR_STOP=1 -d "$COPY" <<SQL
begin;
set local role "$APP_ROLE";
select count(*) from person;
select set_config('app.tenant_id', '$TENANT', true);
select count(*) from person where tenant_id <> '$TENANT';
select count(*) from person;
rollback;
SQL
)"
SEES="$(printf '%s' "$SEES" | grep -E '^[0-9]+$' | tr '\n' ' ')"
OWN="$(q "$COPY" "select count(*) from person where tenant_id = '$TENANT'")"
if [ "$SEES" = "0 0 $OWN " ]; then pass "application role sees nothing without a school and only its own school's people with one"; else fail "application role sees '$SEES' (expected 0 0 $OWN)"; fi

# 4. Privileges and functions came across.
OBJ="select (select count(*) from information_schema.role_table_grants where grantee = '$APP_ROLE' and table_schema = 'public') || '/' ||
            (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public') || '/' ||
            (select count(*) from pg_trigger where not tgisinternal)"
O_SRC="$(q "$SRC" "$OBJ")"
O_COPY="$(q "$COPY" "$OBJ")"
if [ "$O_SRC" = "$O_COPY" ]; then pass "grants, functions and triggers match ($O_COPY)"; else fail "grants/functions/triggers differ: source $O_SRC, copy $O_COPY"; fi

# 5. What a restore from this backup would lose, at worst.
NEWEST="$(q "$COPY" "select coalesce(max(occurred_at)::text, 'no audit entries') from audit_event")"
printf 'INFO  newest audit entry in the backup: %s\n' "$NEWEST"
printf 'INFO  took %s seconds\n' "$(($(date +%s) - START))"

if [ "$FAILED" -ne 0 ]; then
  printf 'Drill FAILED (%s check(s)). Do not rely on this backup.\n' "$FAILED"
  exit 1
fi
printf 'Drill passed: the backup restores and the copy behaves like the original.\n'

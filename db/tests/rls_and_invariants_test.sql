-- Database-level invariant tests. Run after migrations + dev seed:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/tests/rls_and_invariants_test.sql
-- Every test runs as the unprivileged application role, so RLS applies exactly as in production.
-- A failing assertion raises and stops the run with a non-zero exit code.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;

-- T1: no tenant context -> no rows at all (fail closed).
do $$ begin
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from person) <> 0 then raise exception 'T1 failed: rows visible without tenant context'; end if;
  if (select count(*) from tenant) <> 0 then raise exception 'T1 failed: tenants visible without context'; end if;
  raise notice 'T1 pass: no tenant context sees nothing';
end $$;

-- T2: tenant A sees only its own people, campuses and tenant row.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if exists (select 1 from person where tenant_id <> '10000000-0000-4000-8000-000000000001') then
    raise exception 'T2 failed: foreign tenant person visible'; end if;
  if (select count(*) from person where school_ref = 'STU-9001') <> 0 then
    raise exception 'T2 failed: other tenant student visible by business key'; end if;
  if (select count(*) from campus) <> 2 then raise exception 'T2 failed: expected 2 campuses'; end if;
  if (select count(*) from tenant) <> 1 then raise exception 'T2 failed: expected exactly own tenant row'; end if;
  raise notice 'T2 pass: tenant A isolated from tenant B';
end $$;

-- T3: writing a row for another tenant is rejected by WITH CHECK.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into person (tenant_id, school_ref, given_name)
      values ('20000000-0000-4000-8000-000000000001', 'X-1', 'Injected');
    raise exception 'T3 failed: cross-tenant insert allowed';
  exception when insufficient_privilege then
    raise notice 'T3 pass: cross-tenant insert rejected';
  end;
end $$;

-- T4: cross-tenant update affects zero rows (row is invisible).
do $$ declare n int; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  update person set given_name = 'Hijacked' where id = '23000000-0000-4000-8000-000000000001';
  get diagnostics n = row_count;
  if n <> 0 then raise exception 'T4 failed: updated % foreign rows', n; end if;
  raise notice 'T4 pass: cross-tenant update touches nothing';
end $$;

-- T5: audit events are hash-chained and append-only.
do $$ declare s1 bigint; s2 bigint; h1 bytea; p2 bytea; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  insert into audit_event (tenant_id, actor_kind, action, resource_type, outcome, correlation_id)
    values ('10000000-0000-4000-8000-000000000001', 'SYSTEM', 'test.first', 'test', 'SUCCESS', 'corr-1')
    returning seq, hash into s1, h1;
  insert into audit_event (tenant_id, actor_kind, action, resource_type, outcome, correlation_id)
    values ('10000000-0000-4000-8000-000000000001', 'SYSTEM', 'test.second', 'test', 'SUCCESS', 'corr-2')
    returning seq, prev_hash into s2, p2;
  if s2 <> s1 + 1 then raise exception 'T5 failed: seq not contiguous (% then %)', s1, s2; end if;
  if p2 is distinct from h1 then raise exception 'T5 failed: prev_hash does not link to previous hash'; end if;
  begin
    update audit_event set outcome = 'DENY' where seq = s1 and tenant_id = '10000000-0000-4000-8000-000000000001';
    raise exception 'T5 failed: audit update allowed';
  exception when insufficient_privilege then null;
  end;
  begin
    delete from audit_event where seq = s1 and tenant_id = '10000000-0000-4000-8000-000000000001';
    raise exception 'T5 failed: audit delete allowed';
  exception when insufficient_privilege then null;
  end;
  raise notice 'T5 pass: audit chain links and is immutable';
end $$;

-- T6: publishing a config version closes the previous one; old dates keep the old value.
do $$ declare new_id uuid; old_to timestamptz; v_old jsonb; v_new jsonb; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value, effective_from, change_note, created_by)
    values ('10000000-0000-4000-8000-000000000001', 'attendance.late_after_minutes', 'GROUP',
            '10000000-0000-4000-8000-000000000001', 2, '5'::jsonb, '2026-10-01T00:00:00+05:30',
            'Stricter from term 2', '13000000-0000-4000-8000-000000000001')
    returning id into new_id;
  perform config_publish(new_id, '13000000-0000-4000-8000-000000000001');

  select effective_to into old_to from config_version
   where config_key = 'attendance.late_after_minutes' and scope_level = 'GROUP' and version = 1;
  if old_to <> '2026-10-01T00:00:00+05:30'::timestamptz then raise exception 'T6 failed: v1 not closed, effective_to=%', old_to; end if;

  select value into v_old from config_version where config_key = 'attendance.late_after_minutes' and scope_level = 'GROUP'
     and status = 'PUBLISHED' and tstzrange(effective_from, effective_to, '[)') @> '2026-07-15T09:00:00+05:30'::timestamptz;
  select value into v_new from config_version where config_key = 'attendance.late_after_minutes' and scope_level = 'GROUP'
     and status = 'PUBLISHED' and tstzrange(effective_from, effective_to, '[)') @> '2026-11-15T09:00:00+05:30'::timestamptz;
  if v_old <> '10'::jsonb or v_new <> '5'::jsonb then raise exception 'T6 failed: resolved % / %', v_old, v_new; end if;
  raise notice 'T6 pass: effective dating preserves history (July=10, Nov=5)';
end $$;

-- T7: backdated publication before the latest published version is refused.
do $$ declare bad_id uuid; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value, effective_from, change_note, created_by)
    values ('10000000-0000-4000-8000-000000000001', 'attendance.late_after_minutes', 'GROUP',
            '10000000-0000-4000-8000-000000000001', 3, '20'::jsonb, '2026-05-01T00:00:00+05:30',
            'Attempted backdate', '13000000-0000-4000-8000-000000000001')
    returning id into bad_id;
  begin
    perform config_publish(bad_id, '13000000-0000-4000-8000-000000000001');
    raise exception 'T7 failed: backdated publish allowed';
  exception when check_violation then
    raise notice 'T7 pass: backdated publication refused';
  end;
end $$;

-- T8: overlapping academic years on one campus are rejected.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on)
      values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '2026-27b', '2027-01-01', '2027-12-31');
    raise exception 'T8 failed: overlapping academic year accepted';
  exception when exclusion_violation then
    raise notice 'T8 pass: overlapping academic years rejected';
  end;
  -- Next year's calendar can be created without touching the current one (M04 acceptance precondition).
  insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '2027-28', '2027-04-01', '2028-03-31');
end $$;

-- T9: idempotency keys are unique per tenant+scope, and the same key is independent across tenants.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  insert into idempotency_record (tenant_id, scope, idem_key, request_hash)
    values ('10000000-0000-4000-8000-000000000001', 'payment.webhook', 'evt_000000001', sha256('a'::bytea));
  begin
    insert into idempotency_record (tenant_id, scope, idem_key, request_hash)
      values ('10000000-0000-4000-8000-000000000001', 'payment.webhook', 'evt_000000001', sha256('a'::bytea));
    raise exception 'T9 failed: duplicate idempotency key accepted';
  exception when unique_violation then null;
  end;
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  insert into idempotency_record (tenant_id, scope, idem_key, request_hash)
    values ('20000000-0000-4000-8000-000000000001', 'payment.webhook', 'evt_000000001', sha256('a'::bytea));
  raise notice 'T9 pass: idempotency unique per tenant and scope';
end $$;

-- T10: break-glass requires a real justification and at most 4 hours.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into break_glass_grant (tenant_id, person_id, resource_type, resource_id, justification, expires_at)
      values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'case',
              gen_random_uuid(), 'urgent', now() + interval '1 hour');
    raise exception 'T10 failed: short justification accepted';
  exception when check_violation then null;
  end;
  begin
    insert into break_glass_grant (tenant_id, person_id, resource_type, resource_id, justification, expires_at)
      values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'case',
              gen_random_uuid(), 'Child missing at dispersal, need emergency contacts', now() + interval '1 day');
    raise exception 'T10 failed: 24h break-glass accepted';
  exception when check_violation then null;
  end;
  raise notice 'T10 pass: break-glass constraints enforced';
end $$;

-- T11: the login resolver finds identities across tenants without exposing other columns.
do $$ declare n int; begin
  perform set_config('app.tenant_id', '', true);
  select count(*) into n from auth_resolve_identity('dev', 'dev-meera');
  if n <> 1 then raise exception 'T11 failed: expected 1 identity, got %', n; end if;
  select count(*) into n from auth_resolve_identity('dev', 'nobody');
  if n <> 0 then raise exception 'T11 failed: unknown subject resolved'; end if;
  raise notice 'T11 pass: login identity resolution is narrow';
end $$;

-- T12: the application role cannot read audit rows of another tenant even with a guessed id.
do $$ begin
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if exists (select 1 from audit_event) then raise exception 'T12 failed: tenant B sees tenant A audit'; end if;
  raise notice 'T12 pass: audit trail is tenant-isolated';
end $$;

rollback;  -- leave the seeded database unchanged
\echo 'ALL DATABASE INVARIANT TESTS PASSED'

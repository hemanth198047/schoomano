-- B10 support grants and audit verification. Run as the schema owner after migrations + dev seeds; rolls back.
-- Fixture: Asha (…01) is the group admin of tenant 1 (Nilgiri).

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local timezone = 'Asia/Kolkata';  -- deliberately not UTC: verification must not depend on the session zone

-- SU1: only a live grant for that school resolves. Expired, revoked, not yet started and other schools do not.
do $$ declare slug text; begin
  select t.slug into slug from tenant t where t.id = '10000000-0000-4000-8000-000000000001';
  insert into support_access_grant (tenant_id, support_user_ref, approved_by, purpose, starts_at, expires_at) values
    ('10000000-0000-4000-8000-000000000001', 'dev:live',    '13000000-0000-4000-8000-000000000001', 'Help with the fee import', now() - interval '1 hour', now() + interval '2 hours'),
    ('10000000-0000-4000-8000-000000000001', 'dev:expired', '13000000-0000-4000-8000-000000000001', 'Help with the fee import', now() - interval '3 hours', now() - interval '1 hour'),
    ('10000000-0000-4000-8000-000000000001', 'dev:later',   '13000000-0000-4000-8000-000000000001', 'Help with the fee import', now() + interval '1 hour', now() + interval '2 hours');
  insert into support_access_grant (tenant_id, support_user_ref, approved_by, purpose, starts_at, expires_at, revoked_at, revoked_by) values
    ('10000000-0000-4000-8000-000000000001', 'dev:revoked', '13000000-0000-4000-8000-000000000001', 'Help with the fee import', now() - interval '1 hour', now() + interval '2 hours', now(), '13000000-0000-4000-8000-000000000001');
  if (select count(*) from support_resolve('dev:live', slug)) <> 1 then raise exception 'SU1 failed: live grant not found'; end if;
  if (select count(*) from support_resolve('dev:live', 'other-school')) <> 0 then raise exception 'SU1 failed: grant leaked to another school'; end if;
  if (select count(*) from support_resolve('dev:expired', slug)) <> 0 then raise exception 'SU1 failed: expired grant works'; end if;
  if (select count(*) from support_resolve('dev:later', slug)) <> 0 then raise exception 'SU1 failed: future grant works'; end if;
  if (select count(*) from support_resolve('dev:revoked', slug)) <> 0 then raise exception 'SU1 failed: revoked grant works'; end if;
  if (select count(*) from support_resolve('dev:someone-else', slug)) <> 0 then raise exception 'SU1 failed: unknown person'; end if;
  raise notice 'SU1 pass: only a live grant for that school resolves';
end $$;

-- SU2: a grant is limited to 8 hours and needs a real purpose (existing constraints still hold).
do $$ begin
  begin
    insert into support_access_grant (tenant_id, support_user_ref, approved_by, purpose, starts_at, expires_at)
    values ('10000000-0000-4000-8000-000000000001', 'dev:long', '13000000-0000-4000-8000-000000000001', 'Too long a stay', now(), now() + interval '9 hours');
    raise exception 'SU2 failed: 9 hour grant accepted';
  exception when check_violation then null; end;
  begin
    insert into support_access_grant (tenant_id, support_user_ref, approved_by, purpose, starts_at, expires_at)
    values ('10000000-0000-4000-8000-000000000001', 'dev:vague', '13000000-0000-4000-8000-000000000001', 'help', now(), now() + interval '1 hour');
    raise exception 'SU2 failed: vague purpose accepted';
  exception when check_violation then null; end;
  raise notice 'SU2 pass: grant limits hold';
end $$;

-- SU3: the audit chain verifies, whatever the reader's time zone; a changed row is found; a removed row is found.
do $$ declare t uuid := '10000000-0000-4000-8000-000000000001'; r record; s bigint; begin
  for i in 1..3 loop
    insert into audit_event (tenant_id, actor_kind, action, resource_type, resource_id, outcome, details)
    values (t, 'USER', 'test.event', 'thing', i::text, 'SUCCESS', '{"n": 1}'::jsonb);
  end loop;
  select * into r from audit_verify(t);
  if r.broken_seq is not null then raise exception 'SU3 failed: clean chain reported broken at % (%)', r.broken_seq, r.problem; end if;
  set local timezone = 'America/Los_Angeles';
  select * into r from audit_verify(t);
  if r.broken_seq is not null then raise exception 'SU3 failed: verification depends on the reader zone (%)', r.problem; end if;
  set local timezone = 'Asia/Kolkata';

  alter table audit_event disable trigger audit_event_no_update;
  select max(seq) - 1 into s from audit_event where tenant_id = t;
  update audit_event set details = '{"n": 2}'::jsonb where tenant_id = t and seq = s;
  select * into r from audit_verify(t);
  if r.broken_seq is distinct from s then raise exception 'SU3 failed: changed row not found (got %)', r.broken_seq; end if;
  update audit_event set details = '{"n": 1}'::jsonb where tenant_id = t and seq = s;
  select * into r from audit_verify(t);
  if r.broken_seq is not null then raise exception 'SU3 failed: restored row still flagged'; end if;

  delete from audit_event where tenant_id = t and seq = s;
  select * into r from audit_verify(t);
  if r.broken_seq is distinct from s + 1 then raise exception 'SU3 failed: removed row not found (got %)', r.broken_seq; end if;
  alter table audit_event enable trigger audit_event_no_update;
  raise notice 'SU3 pass: audit verification finds changed and removed rows in any zone';
end $$;

-- SU4: the application role can resolve and verify but cannot see another school's grants or audit.
do $$ declare r record; begin
  set local role schoomano_app;
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from support_access_grant) <> 0 then raise exception 'SU4 failed: grants visible across schools'; end if;
  select * into r from audit_verify('10000000-0000-4000-8000-000000000001');
  if r.checked <> 0 then raise exception 'SU4 failed: another school''s audit readable (%)', r.checked; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  update support_access_grant set revoked_at = now(), revoked_by = '13000000-0000-4000-8000-000000000001' where support_user_ref = 'dev:live';
  reset role;
  if (select count(*) from support_resolve('dev:live', (select slug from tenant where id = '10000000-0000-4000-8000-000000000001'))) <> 0 then
    raise exception 'SU4 failed: revoking did not end access'; end if;
  raise notice 'SU4 pass: isolation and revocation';
end $$;

rollback;

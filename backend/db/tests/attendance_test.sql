-- B6 attendance invariants. Run after migrations + dev seeds, as the application role; rolls back.
-- Fixture: Anaya (…20) is enrolled in CBSE grade 5 section A at North; Ravi (…02) is a teacher; Asha (…01) is the office.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.sec() returns uuid language sql as $$ select md5('section-CBSE-K12-5-A')::uuid $$;
create function pg_temp.sess(d date) returns uuid language sql as $$
  insert into attendance_session (tenant_id, class_section_id, on_date)
  values ('10000000-0000-4000-8000-000000000001', pg_temp.sec(), d)
  on conflict (tenant_id, class_section_id, on_date) do update set on_date = excluded.on_date returning id $$;

-- AT1: isolation.
do $$ declare s uuid; begin
  s := pg_temp.sess('2026-09-01');
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from attendance_session) <> 0 then raise exception 'AT1 failed: other tenant sees sessions'; end if;
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from attendance_session) <> 0 then raise exception 'AT1 failed: visible without tenant'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  raise notice 'AT1 pass: attendance isolated per tenant';
end $$;

-- AT2: create, repeat (unchanged), update with the right base version, history kept.
do $$ declare s uuid; r text; begin
  s := pg_temp.sess('2026-09-01');
  r := attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'PRESENT', 0, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  if r <> 'CREATED' then raise exception 'AT2 failed: expected CREATED, got %', r; end if;
  r := attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'PRESENT', 0, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  if r <> 'UNCHANGED' then raise exception 'AT2 failed: expected UNCHANGED, got %', r; end if;
  r := attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'ABSENT', 1, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  if r <> 'UPDATED' then raise exception 'AT2 failed: expected UPDATED, got %', r; end if;
  if (select version from attendance_mark where session_id = s) <> 2 then raise exception 'AT2 failed: version'; end if;
  if (select count(*) from attendance_mark_history h join attendance_mark m on m.id = h.mark_id where m.session_id = s) <> 2 then
    raise exception 'AT2 failed: history rows';
  end if;
  raise notice 'AT2 pass: create, unchanged, update, history';
end $$;

-- AT3: the office corrects while the teacher is offline; the teacher's stale change becomes a visible conflict.
do $$ declare s uuid; r text; c uuid; begin
  s := pg_temp.sess('2026-09-02');
  perform attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'ABSENT', 0, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  perform attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'LEAVE', 1, '13000000-0000-4000-8000-000000000001', now(), 'OFFICE');
  -- the phone still believes version 1 and now says PRESENT
  r := attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'PRESENT', 1, '13000000-0000-4000-8000-000000000002', now() - interval '1 hour', 'DEVICE');
  if r <> 'CONFLICT' then raise exception 'AT3 failed: expected CONFLICT, got %', r; end if;
  if (select status from attendance_mark where session_id = s) <> 'LEAVE' then raise exception 'AT3 failed: current value was overwritten'; end if;
  -- retrying the same stale change does not stack conflicts
  perform attendance_apply(s, '13000000-0000-4000-8000-000000000020', 'PRESENT', 1, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  select id into c from attendance_conflict where state = 'OPEN';
  if (select count(*) from attendance_conflict where state = 'OPEN') <> 1 then raise exception 'AT3 failed: duplicate conflicts'; end if;
  if (select current_status || '/' || attempted_status from attendance_conflict where id = c) <> 'LEAVE/PRESENT' then
    raise exception 'AT3 failed: conflict does not show both values';
  end if;
  raise notice 'AT3 pass: stale device change is a visible conflict, nothing overwritten';
end $$;

-- AT4: resolving keeps history; both outcomes work.
do $$ declare s uuid; c uuid; s2 uuid; c2 uuid; begin
  s := pg_temp.sess('2026-09-02');
  select id into c from attendance_conflict where state = 'OPEN';
  perform attendance_resolve(c, false, '13000000-0000-4000-8000-000000000001');
  if (select status from attendance_mark where session_id = s) <> 'LEAVE' then raise exception 'AT4 failed: kept-current changed value'; end if;
  if (select resolution from attendance_conflict where id = c) <> 'KEPT_CURRENT' then raise exception 'AT4 failed: resolution'; end if;
  begin
    perform attendance_resolve(c, true, '13000000-0000-4000-8000-000000000001');
    raise exception 'AT4 failed: resolved twice';
  exception when check_violation then null; end;
  s2 := pg_temp.sess('2026-09-03');
  perform attendance_apply(s2, '13000000-0000-4000-8000-000000000020', 'ABSENT', 0, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  perform attendance_apply(s2, '13000000-0000-4000-8000-000000000020', 'LATE', 1, '13000000-0000-4000-8000-000000000001', now(), 'OFFICE');
  perform attendance_apply(s2, '13000000-0000-4000-8000-000000000020', 'PRESENT', 1, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
  select id into c2 from attendance_conflict where state = 'OPEN';
  perform attendance_resolve(c2, true, '13000000-0000-4000-8000-000000000001');
  if (select status || version from attendance_mark where session_id = s2) <> 'PRESENT3' then raise exception 'AT4 failed: attempt not applied'; end if;
  if (select count(*) from attendance_mark_history h join attendance_mark m on m.id = h.mark_id where m.session_id = s2) <> 3 then
    raise exception 'AT4 failed: history after resolution';
  end if;
  raise notice 'AT4 pass: conflicts resolve either way and history is kept';
end $$;

-- AT5: history is append-only.
do $$ begin
  begin update attendance_mark_history set status = 'PRESENT'; raise exception 'AT5 failed: history updated';
  exception when check_violation or insufficient_privilege then null; end;
  begin delete from attendance_mark_history; raise exception 'AT5 failed: history deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'AT5 pass: attendance history is append-only';
end $$;

-- AT6: only enrolled students; no future days; inside the year.
do $$ declare s uuid; begin
  s := pg_temp.sess('2026-09-04');
  begin
    perform attendance_apply(s, '13000000-0000-4000-8000-000000000021', 'PRESENT', 0, '13000000-0000-4000-8000-000000000002', now(), 'DEVICE');
    raise exception 'AT6 failed: student of another class accepted';
  exception when check_violation then null; end;
  begin
    perform pg_temp.sess(((now() at time zone 'Asia/Kolkata')::date + 1));
    raise exception 'AT6 failed: future day accepted';
  exception when check_violation then null; end;
  begin
    perform pg_temp.sess('2020-01-06');
    raise exception 'AT6 failed: date outside the year accepted';
  exception when check_violation then null; end;
  raise notice 'AT6 pass: enrolment, future and year-range guards';
end $$;

-- AT7: idempotency keys are unique per school.
do $$ declare s uuid; begin
  s := pg_temp.sess('2026-09-05');
  insert into attendance_submission (tenant_id, idempotency_key, session_id, request_hash, response, created_by)
  values ('10000000-0000-4000-8000-000000000001', 'key-0000001', s, 'h', '{}', '13000000-0000-4000-8000-000000000002');
  begin
    insert into attendance_submission (tenant_id, idempotency_key, session_id, request_hash, response, created_by)
    values ('10000000-0000-4000-8000-000000000001', 'key-0000001', s, 'h', '{}', '13000000-0000-4000-8000-000000000002');
    raise exception 'AT7 failed: duplicate key accepted';
  exception when unique_violation then null; end;
  begin
    insert into attendance_submission (tenant_id, idempotency_key, session_id, request_hash, response, created_by)
    values ('10000000-0000-4000-8000-000000000001', 'short', s, 'h', '{}', '13000000-0000-4000-8000-000000000002');
    raise exception 'AT7 failed: short key accepted';
  exception when check_violation then null; end;
  raise notice 'AT7 pass: idempotency keys';
end $$;

rollback;

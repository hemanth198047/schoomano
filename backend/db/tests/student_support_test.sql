-- B14 student support. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.open_case(student uuid) returns uuid language sql as $$
  insert into student_support_case (tenant_id, student_person_id, category, title, opened_by)
  values ('10000000-0000-4000-8000-000000000001', student, 'WELLBEING', 'Settling in', '13000000-0000-4000-8000-000000000001')
  returning id $$;

-- SS1: a case needs an enrolled student; the child and title are fixed.
do $$ declare c uuid; begin
  begin perform pg_temp.open_case('13000000-0000-4000-8000-000000000002'); raise exception 'SS1 failed: case for a non-student accepted';
  exception when check_violation then null; end;
  c := pg_temp.open_case('13000000-0000-4000-8000-000000000020');
  begin update student_support_case set title = 'Other' where id = c; raise exception 'SS1 failed: title changed';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'SS1 pass: enrolled student, fixed subject';
end $$;

-- SS2: closing needs a reason, is final, and ends notes.
do $$ declare c uuid := (select id from student_support_case limit 1); begin
  insert into support_case_note (tenant_id, case_id, body, author_person_id)
    values ('10000000-0000-4000-8000-000000000001', c, 'Met with the family', '13000000-0000-4000-8000-000000000001');
  update student_support_case set status = 'MONITORING', review_on = current_date + 14 where id = c;
  begin update student_support_case set status = 'CLOSED', closed_at = now() where id = c; raise exception 'SS2 failed: closed without a reason';
  exception when check_violation then null; end;
  update student_support_case set status = 'CLOSED', closed_at = now(), closed_reason = 'Settled well, no further concerns' where id = c;
  begin update student_support_case set status = 'OPEN', closed_at = null where id = c; raise exception 'SS2 failed: reopened';
  exception when check_violation then null; end;
  begin
    insert into support_case_note (tenant_id, case_id, body, author_person_id)
      values ('10000000-0000-4000-8000-000000000001', c, 'Late note', '13000000-0000-4000-8000-000000000001');
    raise exception 'SS2 failed: note added to a closed case';
  exception when check_violation then null; end;
  raise notice 'SS2 pass: closure is final';
end $$;

-- SS3: notes are permanent.
do $$ begin
  begin update support_case_note set body = 'edited'; raise exception 'SS3 failed: note edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from support_case_note; raise exception 'SS3 failed: note deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'SS3 pass: notes are permanent';
end $$;

-- SS4: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from student_support_case) + (select count(*) from support_case_note) <> 0 then
    raise exception 'SS4 failed: support data visible across schools'; end if;
  raise notice 'SS4 pass: isolation';
end $$;
rollback;

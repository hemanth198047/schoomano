-- B62 class teachers: only teachers, never edited or deleted, ended once. Run as the schema owner after migrations and
-- dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into person (id, tenant_id, school_ref, given_name, family_name) values
  ('13000000-0000-4000-8000-0000000000c1', '10000000-0000-4000-8000-000000000001', 'STF-CT-1', 'Not', 'Teacher');
create temp table sec as
  select s.id from class_section s where s.tenant_id = '10000000-0000-4000-8000-000000000001' limit 1;
grant select on sec to schoomano_app;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- C1: someone without the teacher role cannot be class teacher.
do $$ begin
  begin
    insert into class_teacher (tenant_id, class_section_id, teacher_person_id, assigned_by)
      select '10000000-0000-4000-8000-000000000001', id, '13000000-0000-4000-8000-0000000000c1', '13000000-0000-4000-8000-000000000001' from sec;
    raise exception 'C1 failed: a non-teacher was assigned';
  exception when check_violation then null; end;
  raise notice 'C1 pass: only a teacher can be class teacher';
end $$;

-- C2: a teacher can be assigned, but not twice at the same time.
insert into class_teacher (tenant_id, class_section_id, teacher_person_id, assigned_by)
  select '10000000-0000-4000-8000-000000000001', id, '13000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000001' from sec;
do $$ begin
  begin
    insert into class_teacher (tenant_id, class_section_id, teacher_person_id, assigned_by)
      select '10000000-0000-4000-8000-000000000001', id, '13000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000001' from sec;
    raise exception 'C2 failed: assigned twice';
  exception when unique_violation then null; end;
  raise notice 'C2 pass: one live assignment per teacher and section';
end $$;

-- C3: the record cannot be moved to another teacher or deleted.
do $$ begin
  begin
    update class_teacher set teacher_person_id = '13000000-0000-4000-8000-000000000001';
    raise exception 'C3 failed: assignment edited';
  exception when check_violation then null; end;
  begin
    delete from class_teacher;
    raise exception 'C3 failed: assignment deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'C3 pass: assignments are not edited or deleted';
end $$;

-- C4: ending needs who and why (10+ characters), works once, and cannot be undone.
do $$ begin
  begin
    update class_teacher set ended_at = now(), ended_by = '13000000-0000-4000-8000-000000000001', end_reason = 'short';
    raise exception 'C4 failed: ended without a proper reason';
  exception when check_violation then null; end;
  update class_teacher set ended_at = now(), ended_by = '13000000-0000-4000-8000-000000000001', end_reason = 'Moved to Grade 6';
  begin
    update class_teacher set ended_at = null, ended_by = null, end_reason = null;
    raise exception 'C4 failed: ending undone';
  exception when check_violation then null; end;
  raise notice 'C4 pass: ending is final and needs a reason';
end $$;
rollback;

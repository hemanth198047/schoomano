-- B20 co-curricular. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into activity (id, tenant_id, campus_id, academic_year_id, name, kind, capacity) values
  ('1d000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'Chess Club', 'CLUB', 1);
insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: only enrolled students join, once at a time.
do $$ begin
  begin insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004');
    raise exception 'T1 failed: non-student joined';
  exception when check_violation then null; end;
  insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020');
  begin insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020');
    raise exception 'T1 failed: joined twice';
  exception when unique_violation or check_violation then null; end;
  raise notice 'T1 pass: membership is consistent';
end $$;

-- T2: the seat limit holds.
do $$ begin
  begin insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021');
    raise exception 'T2 failed: over the limit';
  exception when check_violation then if sqlerrm <> 'activity full' then raise; end if; end;
  raise notice 'T2 pass: the limit holds';
end $$;

-- T3: attendance is for members, not the future, once per session, and permanent.
do $$ begin
  insert into activity_session (id, tenant_id, activity_id, session_date) values
    ('1d000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', current_date);
  insert into activity_attendance (tenant_id, session_id, student_person_id, present, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020', true, '13000000-0000-4000-8000-000000000002');
  begin insert into activity_attendance (tenant_id, session_id, student_person_id, present, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020', false, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: marked twice';
  exception when unique_violation then null; end;
  begin insert into activity_attendance (tenant_id, session_id, student_person_id, present, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000021', true, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: non-member marked';
  exception when check_violation then null; end;
  begin update activity_attendance set present = false;
    raise exception 'T3 failed: attendance edited';
  exception when insufficient_privilege or check_violation then null; end;
  insert into activity_session (id, tenant_id, activity_id, session_date) values
    ('1d000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', current_date + 3);
  begin insert into activity_attendance (tenant_id, session_id, student_person_id, present, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000003', '13000000-0000-4000-8000-000000000020', true, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: future session marked';
  exception when check_violation then null; end;
  raise notice 'T3 pass: attendance is honest and permanent';
end $$;

-- T4: withdrawn stays withdrawn, and frees the seat; closed activities take nobody.
do $$ begin
  update activity_member set status = 'WITHDRAWN', left_on = current_date + 1 where student_person_id = '13000000-0000-4000-8000-000000000020';
  begin update activity_member set status = 'ACTIVE', left_on = null where student_person_id = '13000000-0000-4000-8000-000000000020';
    raise exception 'T4 failed: withdrawn came back';
  exception when check_violation then null; end;
  insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021');
  update activity set status = 'CLOSED';
  begin insert into activity_member (tenant_id, activity_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020');
    raise exception 'T4 failed: closed activity took a member';
  exception when check_violation then null; end;
  raise notice 'T4 pass: lifecycle holds';
end $$;

-- T5: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from activity) <> 0 or (select count(*) from activity_member) <> 0 then raise exception 'T5 failed: leak'; end if;
  raise notice 'T5 pass: tenants are isolated';
end $$;
rollback;

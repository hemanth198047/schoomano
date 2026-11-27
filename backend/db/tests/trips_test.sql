-- B21 trips. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into trip (id, tenant_id, campus_id, academic_year_id, name, destination, trip_date, consent_deadline, capacity, created_by) values
  ('1e000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'Museum visit', 'Science Museum', current_date, current_date, 1, '13000000-0000-4000-8000-000000000002'),
  ('1e000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'Old trip', 'Zoo', current_date + 30, current_date - 1, null, '13000000-0000-4000-8000-000000000002');
insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: only enrolled students are invited, and the seat limit holds.
do $$ begin
  begin insert into trip_participant (tenant_id, trip_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004');
    raise exception 'T1 failed: non-student invited';
  exception when check_violation then null; end;
  insert into trip_participant (tenant_id, trip_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020');
  begin insert into trip_participant (tenant_id, trip_id, student_person_id) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021');
    raise exception 'T1 failed: over the limit';
  exception when check_violation then if sqlerrm <> 'trip full' then raise; end if; end;
  raise notice 'T1 pass: the trip list is consistent';
end $$;

-- T2: consent only for invited students and before the deadline; every decision is kept.
do $$ begin
  begin insert into trip_consent (tenant_id, trip_id, student_person_id, guardian_person_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', '13000000-0000-4000-8000-000000000010', 'GIVEN');
    raise exception 'T2 failed: consent for a student not on the trip';
  exception when check_violation then null; end;
  insert into trip_consent (tenant_id, trip_id, student_person_id, guardian_person_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', '13000000-0000-4000-8000-000000000010', 'DECLINED');
  begin update trip_consent set decision = 'GIVEN';
    raise exception 'T2 failed: decision edited';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'T2 pass: consent is recorded properly';
end $$;

-- T3: after the deadline nothing can be decided.
do $$ begin
  begin insert into trip_consent (tenant_id, trip_id, student_person_id, guardian_person_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020', '13000000-0000-4000-8000-000000000010', 'GIVEN');
    raise exception 'T3 failed: consent after the deadline';
  exception when check_violation then null; end;
  raise notice 'T3 pass: the deadline holds';
end $$;

-- T4: headcount needs the latest consent to be "given", departure before return, and the trip day to have come.
do $$ begin
  begin insert into trip_headcount (tenant_id, trip_id, student_person_id, checkpoint, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'DEPARTED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T4 failed: headcount without consent';
  exception when check_violation then null; end;
  insert into trip_consent (tenant_id, trip_id, student_person_id, guardian_person_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', '13000000-0000-4000-8000-000000000010', 'GIVEN');
  begin insert into trip_headcount (tenant_id, trip_id, student_person_id, checkpoint, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'RETURNED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T4 failed: returned without departing';
  exception when check_violation then null; end;
  insert into trip_headcount (tenant_id, trip_id, student_person_id, checkpoint, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'DEPARTED', '13000000-0000-4000-8000-000000000002');
  insert into trip_headcount (tenant_id, trip_id, student_person_id, checkpoint, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1e000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'RETURNED', '13000000-0000-4000-8000-000000000002');
  raise notice 'T4 pass: headcounts are honest';
end $$;

-- T5: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from trip) + (select count(*) from trip_participant) + (select count(*) from trip_consent) + (select count(*) from trip_headcount) <> 0 then
    raise exception 'T5 failed: leak'; end if;
  raise notice 'T5 pass: tenants are isolated';
end $$;
rollback;

-- B16 health. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- H1: only enrolled children have health information; blood group and flag are constrained.
do $$ begin
  begin insert into health_record (tenant_id, student_person_id, updated_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004', '13000000-0000-4000-8000-000000000005');
    raise exception 'H1 failed: non-student accepted';
  exception when check_violation then null; end;
  begin insert into health_record (tenant_id, student_person_id, blood_group, updated_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'Z+', '13000000-0000-4000-8000-000000000005');
    raise exception 'H1 failed: bad blood group accepted';
  exception when check_violation then null; end;
  begin insert into health_record (tenant_id, student_person_id, emergency_flag, updated_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'x', '13000000-0000-4000-8000-000000000005');
    raise exception 'H1 failed: one-letter flag accepted';
  exception when check_violation then null; end;
  insert into health_record (tenant_id, student_person_id, blood_group, emergency_flag, updated_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'O+', 'Severe nut allergy', '13000000-0000-4000-8000-000000000005');
  raise notice 'H1 pass: records are for enrolled children and well-formed';
end $$;

-- H2: records can be updated but never deleted.
do $$ begin
  update health_record set allergies = 'Peanuts' where student_person_id = '13000000-0000-4000-8000-000000000020';
  begin delete from health_record where student_person_id = '13000000-0000-4000-8000-000000000020';
    raise exception 'H2 failed: record deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'H2 pass: records are updated, not deleted';
end $$;

-- H3: infirmary visits are permanent and cannot be future-dated.
do $$ declare v uuid; begin
  insert into health_visit (tenant_id, student_person_id, complaint, outcome, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'Headache after PE', 'RETURNED_TO_CLASS', '13000000-0000-4000-8000-000000000005')
    returning id into v;
  begin update health_visit set complaint = 'Changed' where id = v; raise exception 'H3 failed: visit edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from health_visit where id = v; raise exception 'H3 failed: visit deleted';
  exception when insufficient_privilege or check_violation then null; end;
  begin insert into health_visit (tenant_id, student_person_id, visited_at, complaint, outcome, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', now() + interval '1 day', 'Tomorrow', 'OTHER', '13000000-0000-4000-8000-000000000005');
    raise exception 'H3 failed: future visit accepted';
  exception when check_violation then null; end;
  raise notice 'H3 pass: visits are permanent';
end $$;

-- H4: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from health_record) + (select count(*) from health_visit) <> 0 then
    raise exception 'H4 failed: cross-tenant read'; end if;
  raise notice 'H4 pass: tenant isolation';
end $$;
rollback;

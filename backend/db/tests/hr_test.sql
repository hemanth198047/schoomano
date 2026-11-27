-- B18 HR. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- HR1: staff only; employee numbers unique; status and leaving date agree.
do $$ begin
  begin insert into staff_profile (tenant_id, person_id, campus_id, employee_no, designation, employment_type, joined_on) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', '12000000-0000-4000-8000-000000000001', 'E-T1', 'Teacher', 'FULL_TIME', '2020-04-01');
    raise exception 'HR1 failed: student given a staff profile';
  exception when check_violation then null; end;
  insert into staff_profile (tenant_id, person_id, campus_id, employee_no, designation, employment_type, joined_on) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001', 'E-T1', 'Teacher', 'FULL_TIME', '2020-04-01');
  begin insert into staff_profile (tenant_id, person_id, campus_id, employee_no, designation, employment_type, joined_on) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'E-T1', 'Admin', 'FULL_TIME', '2020-04-01');
    raise exception 'HR1 failed: duplicate employee number';
  exception when unique_violation then null; end;
  begin update staff_profile set status = 'LEFT' where person_id = '13000000-0000-4000-8000-000000000002';
    raise exception 'HR1 failed: left without a date';
  exception when check_violation then null; end;
  raise notice 'HR1 pass: staff profiles are well-formed';
end $$;

-- HR2: leave cannot overlap another request of the same person.
do $$ begin
  insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'CASUAL', '2026-12-01', '2026-12-03', 3);
  begin insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'SICK', '2026-12-03', '2026-12-04', 2);
    raise exception 'HR2 failed: overlap accepted';
  exception when check_violation then null; end;
  begin insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'SICK', '2026-12-09', '2026-12-08', 1);
    raise exception 'HR2 failed: backwards dates';
  exception when check_violation then null; end;
  raise notice 'HR2 pass: no overlapping or backwards leave';
end $$;

-- HR3: someone else approves, within the allowance; decisions are final; requests cannot be edited.
do $$ declare r uuid; begin
  insert into leave_allowance (tenant_id, campus_id, academic_year_id, leave_type, days) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', 'CASUAL', 4);
  select id into r from leave_request where from_date = '2026-12-01';
  begin update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000002' where id = r;
    raise exception 'HR3 failed: self approval';
  exception when check_violation then null; end;
  begin update leave_request set days = 1 where id = r; raise exception 'HR3 failed: days edited';
  exception when check_violation or insufficient_privilege then null; end;
  update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
  begin update leave_request set status = 'REJECTED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
    raise exception 'HR3 failed: decision changed';
  exception when check_violation then null; end;
  raise notice 'HR3 pass: separate approver and final decisions';
end $$;

-- HR4: approvals stop at the allowance (3 of 4 casual days already taken); unpaid leave has no limit.
do $$ declare r uuid; u uuid; begin
  insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'CASUAL', '2026-12-15', '2026-12-16', 2) returning id into r;
  begin update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
    raise exception 'HR4 failed: over balance approved';
  exception when check_violation then if sqlerrm <> 'insufficient leave balance' then raise; end if; end;
  insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'UNPAID', '2026-12-20', '2026-12-30', 9) returning id into u;
  update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000001' where id = u;
  begin delete from leave_request where id = u; raise exception 'HR4 failed: request deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'HR4 pass: balance enforced, unpaid unlimited';
end $$;

-- HR5: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from staff_profile) + (select count(*) from leave_request) + (select count(*) from leave_allowance) <> 0 then
    raise exception 'HR5 failed: cross-tenant read'; end if;
  raise notice 'HR5 pass: tenant isolation';
end $$;
rollback;

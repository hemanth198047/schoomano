-- B27 leave requests routed through approval chains. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'principal', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'lw test');
insert into staff_profile (tenant_id, person_id, campus_id, employee_no, designation, employment_type, joined_on) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001', 'E-LW1', 'Teacher', 'FULL_TIME', '2020-04-01');
insert into leave_allowance (tenant_id, campus_id, academic_year_id, leave_type, days) values
  ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', 'CASUAL', 10);
insert into workflow_definition (id, tenant_id, campus_id, chain_key, version, name, created_by) values
  ('1b000000-0000-4000-8000-0000000000c1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'lw-test', 1, 'LW chain', '13000000-0000-4000-8000-000000000001');
insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
  ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-0000000000c1', 1, 'principal', 'Principal');
update workflow_definition set status = 'PUBLISHED', published_at = now() where id = '1b000000-0000-4000-8000-0000000000c1';
insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
  ('1b000000-0000-4000-8000-0000000000d1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1b000000-0000-4000-8000-0000000000c1', '13000000-0000-4000-8000-000000000002', 'Leave one'),
  ('1b000000-0000-4000-8000-0000000000d2', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1b000000-0000-4000-8000-0000000000c1', '13000000-0000-4000-8000-000000000002', 'Leave two');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- L1: a leave request can only be linked to an open chain request of the same person, once.
do $$ begin
  begin insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days, workflow_request_id) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     'CASUAL', '2026-12-01', '2026-12-01', 1, '1b000000-0000-4000-8000-0000000000d1');
    raise exception 'L1 failed: linked to another person''s request';
  exception when check_violation or foreign_key_violation then null; end;
  insert into leave_request (id, tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days, workflow_request_id) values
    ('1b000000-0000-4000-8000-0000000000e1', '10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'CASUAL', '2026-12-01', '2026-12-02', 2, '1b000000-0000-4000-8000-0000000000d1');
  begin insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days, workflow_request_id) values
    ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     'CASUAL', '2026-12-10', '2026-12-10', 1, '1b000000-0000-4000-8000-0000000000d1');
    raise exception 'L1 failed: one chain request linked twice';
  exception when unique_violation then null; end;
  raise notice 'L1 pass: links are one to one and personal';
end $$;

-- L2: nobody can approve or reject around the chain; once the chain approves, the leave can be approved.
do $$ begin
  begin update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'L2 failed: approved around the chain';
  exception when check_violation then null; end;
  begin update leave_request set status = 'REJECTED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'L2 failed: rejected around the chain';
  exception when check_violation then null; end;
  begin update leave_request set workflow_request_id = null where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'L2 failed: chain detached';
  exception when check_violation or insufficient_privilege then null; end;
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-0000000000d1', 1, '13000000-0000-4000-8000-000000000003', 'APPROVED');
  update workflow_request set status = 'APPROVED', closed_at = now() where id = '1b000000-0000-4000-8000-0000000000d1';
  update leave_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
  raise notice 'L2 pass: only the chain decides';
end $$;

-- L3: cancelling needs the chain request withdrawn first.
do $$ begin
  insert into leave_request (id, tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days, workflow_request_id) values
    ('1b000000-0000-4000-8000-0000000000e2', '10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', '12000000-0000-4000-8000-000000000001',
     '15000000-0000-4000-8000-000000000001', 'CASUAL', '2026-12-10', '2026-12-10', 1, '1b000000-0000-4000-8000-0000000000d2');
  begin update leave_request set status = 'CANCELLED' where id = '1b000000-0000-4000-8000-0000000000e2';
    raise exception 'L3 failed: cancelled while the chain was open';
  exception when check_violation then null; end;
  update workflow_request set status = 'WITHDRAWN', closed_at = now() where id = '1b000000-0000-4000-8000-0000000000d2';
  update leave_request set status = 'CANCELLED' where id = '1b000000-0000-4000-8000-0000000000e2';
  raise notice 'L3 pass: withdraw then cancel';
end $$;
rollback;

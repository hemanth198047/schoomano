-- B55 workflow: a decider must hold the step's role for the request's campus. Run as the schema owner after migrations and
-- dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into person (id, tenant_id, school_ref, given_name, family_name) values
  ('13000000-0000-4000-8000-0000000000a1', '10000000-0000-4000-8000-000000000001', 'STF-WS-1', 'Scope', 'Tester');
insert into workflow_definition (id, tenant_id, campus_id, chain_key, version, name, created_by) values
  ('1b000000-0000-4000-8000-000000000101', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'wf-scope', 1, 'Scope chain', '13000000-0000-4000-8000-000000000001');
insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
  ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000101', 1, 'principal', 'Principal');
update workflow_definition set status = 'PUBLISHED', published_at = now() where id = '1b000000-0000-4000-8000-000000000101';
insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
  ('1b000000-0000-4000-8000-000000000102', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1b000000-0000-4000-8000-000000000101', '13000000-0000-4000-8000-000000000002', 'Scope test');
-- The person is a principal, but of another campus.
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-0000000000a1', 'principal', 'CAMPUS', '12000000-0000-4000-8000-000000000099', 'wf scope test');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- S1: a principal of another campus cannot decide this campus's request.
do $$ begin
  begin
    insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
      ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000102', 1, '13000000-0000-4000-8000-0000000000a1', 'APPROVED');
    raise exception 'S1 failed: a principal of another campus decided';
  exception when check_violation then null; end;
  raise notice 'S1 pass: the role must cover the request''s campus';
end $$;

-- S2: a grant that has ended, or has not started, does not count.
reset role;
update role_grant set scope_id = '12000000-0000-4000-8000-000000000001', valid_from = now() - interval '2 days', valid_to = now() - interval '1 day'
 where person_id = '13000000-0000-4000-8000-0000000000a1';
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
do $$ begin
  begin
    insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
      ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000102', 1, '13000000-0000-4000-8000-0000000000a1', 'APPROVED');
    raise exception 'S2 failed: an ended grant decided';
  exception when check_violation then null; end;
  raise notice 'S2 pass: an ended grant does not count';
end $$;

-- S3: the same person with a live grant for this campus can decide.
reset role;
update role_grant set valid_from = now() - interval '1 hour', valid_to = null where person_id = '13000000-0000-4000-8000-0000000000a1';
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
do $$ begin
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000102', 1, '13000000-0000-4000-8000-0000000000a1', 'APPROVED');
  raise notice 'S3 pass: a live grant for the campus decides';
end $$;
rollback;

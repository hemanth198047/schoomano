-- B25 workflow. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
-- Ravi (teacher …02) asks; Asha (group admin …01) and Farah (…03) are in the chain by role.
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'principal', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'wf test');
insert into workflow_definition (id, tenant_id, campus_id, chain_key, version, name, created_by) values
  ('1b000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'wf-test', 1, 'Test chain', '13000000-0000-4000-8000-000000000001');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: a chain needs steps, in order, before it can be published; published chains do not change.
do $$ begin
  begin update workflow_definition set status = 'PUBLISHED', published_at = now();
    raise exception 'T1 failed: published without steps';
  exception when check_violation then null; end;
  begin insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000001', 2, 'principal', 'Skipped ahead');
    raise exception 'T1 failed: step out of order';
  exception when check_violation then null; end;
  insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000001', 1, 'principal', 'Principal'),
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000001', 2, 'group_admin', 'Group');
  update workflow_definition set status = 'PUBLISHED', published_at = now();
  begin insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000001', 3, 'principal', 'Late');
    raise exception 'T1 failed: step added after publishing';
  exception when check_violation then null; end;
  begin update workflow_definition set status = 'DRAFT', published_at = null;
    raise exception 'T1 failed: unpublished';
  exception when check_violation then null; end;
  raise notice 'T1 pass: chains are versioned and fixed';
end $$;

-- T2: requests only use a published chain, start at step 1, and cannot be decided by their requester or the wrong role.
do $$ begin
  insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
    ('1b000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Buy a projector');
  begin insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000002', 1, '13000000-0000-4000-8000-000000000002', 'APPROVED');
    raise exception 'T2 failed: requester decided';
  exception when check_violation then null; end;
  begin insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000002', 1, '13000000-0000-4000-8000-000000000001', 'APPROVED');
    raise exception 'T2 failed: wrong role decided';
  exception when check_violation then null; end;
  begin insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000002', 2, '13000000-0000-4000-8000-000000000001', 'APPROVED');
    raise exception 'T2 failed: step out of turn';
  exception when check_violation then null; end;
  raise notice 'T2 pass: only the right person decides in turn';
end $$;

-- T3: approval walks the chain one step at a time and cannot be shortcut.
do $$ begin
  begin update workflow_request set status = 'APPROVED', closed_at = now();
    raise exception 'T3 failed: approved without decisions';
  exception when check_violation then null; end;
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000002', 1, '13000000-0000-4000-8000-000000000003', 'APPROVED');
  begin update workflow_request set status = 'APPROVED', closed_at = now();
    raise exception 'T3 failed: approved before the last step';
  exception when check_violation then null; end;
  update workflow_request set current_step = 2;
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000002', 2, '13000000-0000-4000-8000-000000000001', 'APPROVED');
  update workflow_request set status = 'APPROVED', closed_at = now();
  begin update workflow_request set status = 'REJECTED';
    raise exception 'T3 failed: closed request reopened';
  exception when check_violation then null; end;
  begin update workflow_decision set decision = 'REJECTED';
    raise exception 'T3 failed: decision edited';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'T3 pass: approval is earned step by step';
end $$;

-- T4: a rejection needs a reason, and closes the request.
do $$ begin
  insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
    ('1b000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Another thing');
  begin insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000003', 1, '13000000-0000-4000-8000-000000000003', 'REJECTED');
    raise exception 'T4 failed: rejected without a reason';
  exception when check_violation then null; end;
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision, note) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-000000000003', 1, '13000000-0000-4000-8000-000000000003', 'REJECTED', 'Not this term');
  update workflow_request set status = 'REJECTED', closed_at = now() where id = '1b000000-0000-4000-8000-000000000003';
  raise notice 'T4 pass: rejection is reasoned';
end $$;

-- T5: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from workflow_definition) + (select count(*) from workflow_step) + (select count(*) from workflow_request) + (select count(*) from workflow_decision) <> 0 then
    raise exception 'T5 failed: leak'; end if;
  raise notice 'T5 pass: tenants are isolated';
end $$;
rollback;

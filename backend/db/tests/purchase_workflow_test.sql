-- B26 purchase requests routed through approval chains. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'principal', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'pw test');
insert into budget (id, tenant_id, campus_id, academic_year_id, category, amount_paise) values
  ('1b000000-0000-4000-8000-0000000000b1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'PW budget', 1000000);
insert into workflow_definition (id, tenant_id, campus_id, chain_key, version, name, created_by) values
  ('1b000000-0000-4000-8000-0000000000c1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'pw-test', 1, 'PW chain', '13000000-0000-4000-8000-000000000001');
insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
  ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-0000000000c1', 1, 'principal', 'Principal');
update workflow_definition set status = 'PUBLISHED', published_at = now() where id = '1b000000-0000-4000-8000-0000000000c1';
insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
  ('1b000000-0000-4000-8000-0000000000d1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1b000000-0000-4000-8000-0000000000c1', '13000000-0000-4000-8000-000000000002', 'Projector'),
  ('1b000000-0000-4000-8000-0000000000d2', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1b000000-0000-4000-8000-0000000000c1', '13000000-0000-4000-8000-000000000002', 'Chairs');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- W1: a purchase request can only be linked to an open request of the same person.
do $$ begin
  begin insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by, workflow_request_id) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-0000000000b1', 'Someone else''s', 100, '13000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-0000000000d1');
    raise exception 'W1 failed: linked to another person''s request';
  exception when check_violation then null; end;
  insert into purchase_request (id, tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by, workflow_request_id) values
    ('1b000000-0000-4000-8000-0000000000e1', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-0000000000b1', 'Projector', 50000, '13000000-0000-4000-8000-000000000002', '1b000000-0000-4000-8000-0000000000d1');
  begin insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by, workflow_request_id) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-0000000000b1', 'Twice', 100, '13000000-0000-4000-8000-000000000002', '1b000000-0000-4000-8000-0000000000d1');
    raise exception 'W1 failed: one chain request linked twice';
  exception when unique_violation then null; end;
  raise notice 'W1 pass: links are one to one and personal';
end $$;

-- W2: while the chain is open nobody can approve or reject directly; once approved, the purchase can be.
do $$ begin
  begin update purchase_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'W2 failed: approved around the chain';
  exception when check_violation then null; end;
  begin update purchase_request set status = 'REJECTED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'W2 failed: rejected around the chain';
  exception when check_violation then null; end;
  begin update purchase_request set workflow_request_id = null where id = '1b000000-0000-4000-8000-0000000000e1';
    raise exception 'W2 failed: chain detached';
  exception when check_violation or insufficient_privilege then null; end;
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1b000000-0000-4000-8000-0000000000d1', 1, '13000000-0000-4000-8000-000000000003', 'APPROVED');
  update workflow_request set status = 'APPROVED', closed_at = now() where id = '1b000000-0000-4000-8000-0000000000d1';
  update purchase_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000003' where id = '1b000000-0000-4000-8000-0000000000e1';
  raise notice 'W2 pass: only the chain decides';
end $$;

-- W3: cancelling needs the chain request withdrawn first.
do $$ begin
  insert into purchase_request (id, tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by, workflow_request_id) values
    ('1b000000-0000-4000-8000-0000000000e2', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-0000000000b1', 'Chairs', 20000, '13000000-0000-4000-8000-000000000002', '1b000000-0000-4000-8000-0000000000d2');
  begin update purchase_request set status = 'CANCELLED' where id = '1b000000-0000-4000-8000-0000000000e2';
    raise exception 'W3 failed: cancelled while the chain was open';
  exception when check_violation then null; end;
  update workflow_request set status = 'WITHDRAWN', closed_at = now() where id = '1b000000-0000-4000-8000-0000000000d2';
  update purchase_request set status = 'CANCELLED' where id = '1b000000-0000-4000-8000-0000000000e2';
  raise notice 'W3 pass: withdraw then cancel';
end $$;
rollback;

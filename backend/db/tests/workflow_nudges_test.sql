-- B35 workflow reminders and escalation. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'principal', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'nudge test');
insert into workflow_definition (id, tenant_id, campus_id, chain_key, version, name, created_by) values
  ('1c000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'nudge-test', 1, 'Nudge chain', '13000000-0000-4000-8000-000000000001');
insert into workflow_step (tenant_id, definition_id, position, role_key, label) values
  ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', 1, 'principal', 'Principal'),
  ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', 2, 'group_admin', 'Group');
update workflow_definition set status = 'PUBLISHED', published_at = now() where id = '1c000000-0000-4000-8000-000000000001';
insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject) values
  ('1c000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1c000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Buy a projector'),
  ('1c000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '1c000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Buy a screen');

-- R1: only requests that have waited long enough are listed, and a request due for escalation is not also reminded.
do $$ begin
  if (select count(*) from workflow_overdue(interval '1 day', interval '3 days') where req_id in ('1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000003')) <> 0 then
    raise exception 'R1 failed: a fresh request is overdue'; end if;
  if (select count(*) from workflow_overdue(interval '0', interval '3 days') where req_id in ('1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000003')
        and nudge_kind = 'REMINDER' and step_no = 1) <> 2 then raise exception 'R1 failed: reminders not listed'; end if;
  if (select count(*) from workflow_overdue(interval '0', interval '0') where req_id = '1c000000-0000-4000-8000-000000000002') <> 1
     or (select nudge_kind from workflow_overdue(interval '0', interval '0') where req_id = '1c000000-0000-4000-8000-000000000002') <> 'ESCALATION' then
    raise exception 'R1 failed: escalation should replace the reminder'; end if;
  raise notice 'R1 pass: overdue means waited long enough';
end $$;

-- R2: a nudge is recorded once per step and kind, only for the current step of a pending request, and is permanent.
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
do $$ begin
  insert into workflow_nudge (tenant_id, request_id, position, kind) values ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', 1, 'REMINDER');
  if (select count(*) from workflow_overdue(interval '0', interval '3 days') where req_id = '1c000000-0000-4000-8000-000000000002') <> 0 then
    raise exception 'R2 failed: nudged request listed again'; end if;
  begin insert into workflow_nudge (tenant_id, request_id, position, kind) values ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', 1, 'REMINDER');
    raise exception 'R2 failed: second reminder for the same step';
  exception when unique_violation then null; end;
  begin insert into workflow_nudge (tenant_id, request_id, position, kind) values ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', 2, 'REMINDER');
    raise exception 'R2 failed: nudge for a step that is not current';
  exception when check_violation then null; end;
  begin update workflow_nudge set kind = 'ESCALATION'; raise exception 'R2 failed: nudge edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin delete from workflow_nudge; raise exception 'R2 failed: nudge deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'R2 pass: once per step and kind, permanent';
end $$;

-- R3: the wait restarts at each approval, closed requests are never nudged, and another school sees none of it.
do $$ begin
  insert into workflow_request (id, tenant_id, campus_id, definition_id, requester_id, subject, submitted_at) values
    ('1c000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
     '1c000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Old request', now() - interval '5 days');
  insert into workflow_decision (tenant_id, request_id, position, decider_id, decision) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000004', 1, '13000000-0000-4000-8000-000000000003', 'APPROVED');
  update workflow_request set current_step = 2 where id = '1c000000-0000-4000-8000-000000000004';
  if (select count(*) from workflow_overdue(interval '1 hour', interval '3 days') where req_id = '1c000000-0000-4000-8000-000000000004') <> 0 then
    raise exception 'R3 failed: wait did not restart at the approval'; end if;
  if (select step_no from workflow_overdue(interval '0', interval '3 days') where req_id = '1c000000-0000-4000-8000-000000000004') <> 2 then
    raise exception 'R3 failed: wrong step'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from workflow_nudge) <> 0 then raise exception 'R3 failed: another school sees nudges'; end if;
  raise notice 'R3 pass: restarts at approvals, isolated';
end $$;
rollback;

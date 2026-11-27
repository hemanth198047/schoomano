-- B23 compliance register. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into compliance_item (id, tenant_id, campus_id, title, category, owner_name, repeat_months, next_due_on, created_by) values
  ('1c000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   'Fire safety certificate', 'FIRE_SAFETY', 'Estates head', 12, (now() at time zone 'Asia/Kolkata')::date + 10, '13000000-0000-4000-8000-000000000002');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: items cannot be edited; only the due date and the status move.
do $$ begin
  begin update compliance_item set title = 'Renamed';
    raise exception 'T1 failed: item renamed';
  exception when insufficient_privilege or check_violation then null; end;
  begin insert into compliance_item (tenant_id, campus_id, title, category, owner_name, created_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Fire safety certificate', 'FIRE_SAFETY', 'Someone', '13000000-0000-4000-8000-000000000002');
    raise exception 'T1 failed: duplicate title';
  exception when unique_violation then null; end;
  raise notice 'T1 pass: items are stable';
end $$;

-- T2: the due date only moves forward.
do $$ begin
  update compliance_item set next_due_on = (now() at time zone 'Asia/Kolkata')::date + 400;
  begin update compliance_item set next_due_on = (now() at time zone 'Asia/Kolkata')::date + 5;
    raise exception 'T2 failed: due date moved back';
  exception when check_violation then null; end;
  raise notice 'T2 pass: due dates only move forward';
end $$;

-- T3: evidence is permanent, not future-dated, and not for retired items.
do $$ begin
  insert into compliance_evidence (tenant_id, item_id, done_on, reference, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', (now() at time zone 'Asia/Kolkata')::date, 'FS-2026-114', '13000000-0000-4000-8000-000000000002');
  begin update compliance_evidence set reference = 'changed';
    raise exception 'T3 failed: evidence edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin insert into compliance_evidence (tenant_id, item_id, done_on, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', (now() at time zone 'Asia/Kolkata')::date + 3, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: future evidence';
  exception when check_violation then null; end;
  begin insert into compliance_evidence (tenant_id, item_id, done_on, valid_until, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', (now() at time zone 'Asia/Kolkata')::date, (now() at time zone 'Asia/Kolkata')::date - 1, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: expiry before the date done';
  exception when check_violation then null; end;
  update compliance_item set status = 'RETIRED';
  begin insert into compliance_evidence (tenant_id, item_id, done_on, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000001', (now() at time zone 'Asia/Kolkata')::date, '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: evidence for a retired item';
  exception when check_violation then null; end;
  begin update compliance_item set status = 'ACTIVE';
    raise exception 'T3 failed: retired item came back';
  exception when check_violation then null; end;
  raise notice 'T3 pass: evidence is permanent';
end $$;

-- T4: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from compliance_item) + (select count(*) from compliance_evidence) <> 0 then raise exception 'T4 failed: leak'; end if;
  raise notice 'T4 pass: tenants are isolated';
end $$;
rollback;

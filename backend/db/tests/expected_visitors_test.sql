-- B32 pre-registered visitors. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- E1: announcements are permanent; they close once, and arrival needs the matching visit.
do $$ declare e uuid; v uuid; begin
  insert into expected_visitor (tenant_id, campus_id, host_person_id, visitor_name, purpose, expected_on) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Guest One', 'Meeting', current_date) returning id into e;
  begin update expected_visitor set visitor_name = 'Someone else' where id = e; raise exception 'E1 failed: edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin delete from expected_visitor where id = e; raise exception 'E1 failed: deleted';
  exception when check_violation or insufficient_privilege then null; end;
  begin insert into expected_visitor (tenant_id, campus_id, host_person_id, visitor_name, purpose, expected_on, status) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Pre-closed', 'Meeting', current_date, 'CANCELLED');
    raise exception 'E1 failed: inserted closed';
  exception when check_violation then null; end;
  insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, host_person_id, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Somebody Else', 'Meeting', '13000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000001') returning id into v;
  begin update expected_visitor set status = 'ARRIVED', visit_id = v where id = e; raise exception 'E1 failed: arrived with another person''s visit';
  exception when check_violation then null; end;
  insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, host_person_id, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Guest One', 'Meeting', '13000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000001') returning id into v;
  update expected_visitor set status = 'ARRIVED', visit_id = v where id = e;
  begin update expected_visitor set status = 'CANCELLED', visit_id = null where id = e; raise exception 'E1 failed: reopened or cancelled after arrival';
  exception when check_violation then null; end;
  raise notice 'E1 pass: permanent announcements, arrival matches the visit';
end $$;

-- E2: cancelling is final, and an arrival needs a visit.
do $$ declare e uuid; begin
  insert into expected_visitor (tenant_id, campus_id, host_person_id, visitor_name, purpose, expected_on) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Guest Two', 'Meeting', current_date) returning id into e;
  begin update expected_visitor set status = 'ARRIVED' where id = e; raise exception 'E2 failed: arrived without a visit';
  exception when check_violation then null; end;
  update expected_visitor set status = 'CANCELLED' where id = e;
  begin update expected_visitor set status = 'EXPECTED' where id = e; raise exception 'E2 failed: cancelled then reopened';
  exception when check_violation then null; end;
  raise notice 'E2 pass: closing is final';
end $$;

-- E3: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from expected_visitor) <> 0 then raise exception 'E3 failed: leak'; end if;
  raise notice 'E3 pass: tenants are isolated';
end $$;
rollback;

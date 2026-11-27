-- B28 visitor log. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- V1: entries are permanent and cannot be edited; check-out happens once.
do $$ declare v uuid; begin
  insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, host_person_id, badge_no, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Test Visitor', 'Parent meeting',
     '13000000-0000-4000-8000-000000000002', 'B-1', '13000000-0000-4000-8000-000000000001') returning id into v;
  begin update visitor_visit set visitor_name = 'Someone else' where id = v; raise exception 'V1 failed: name edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin delete from visitor_visit where id = v; raise exception 'V1 failed: deleted';
  exception when check_violation or insufficient_privilege then null; end;
  begin insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, checked_in_by, checked_out_at, checked_out_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Already gone', 'Delivery',
     '13000000-0000-4000-8000-000000000001', now(), '13000000-0000-4000-8000-000000000001');
    raise exception 'V1 failed: inserted already checked out';
  exception when check_violation then null; end;
  update visitor_visit set checked_out_at = clock_timestamp(), checked_out_by = '13000000-0000-4000-8000-000000000001' where id = v;
  begin update visitor_visit set checked_out_at = clock_timestamp(), checked_out_by = '13000000-0000-4000-8000-000000000001' where id = v;
    raise exception 'V1 failed: checked out twice';
  exception when check_violation then null; end;
  raise notice 'V1 pass: permanent entries, one check-out';
end $$;

-- V2: a badge is on one person at a time, and free again after check-out.
do $$ declare v uuid; begin
  insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, badge_no, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'First', 'Interview', 'B-2', '13000000-0000-4000-8000-000000000001') returning id into v;
  begin insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, badge_no, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Second', 'Interview', 'B-2', '13000000-0000-4000-8000-000000000001');
    raise exception 'V2 failed: badge shared';
  exception when unique_violation then null; end;
  update visitor_visit set checked_out_at = clock_timestamp(), checked_out_by = '13000000-0000-4000-8000-000000000001' where id = v;
  insert into visitor_visit (tenant_id, campus_id, visitor_name, purpose, badge_no, checked_in_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Third', 'Interview', 'B-2', '13000000-0000-4000-8000-000000000001');
  raise notice 'V2 pass: badges are reusable after check-out';
end $$;

-- V3: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from visitor_visit) <> 0 then raise exception 'V3 failed: leak'; end if;
  raise notice 'V3 pass: tenants are isolated';
end $$;
rollback;

-- B19 transport. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into vehicle (id, tenant_id, campus_id, registration, capacity) values
  ('1c000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'KA01TEST1', 1);
insert into transport_route (id, tenant_id, campus_id, name, vehicle_id, driver_name) values
  ('1c000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Route T1', '1c000000-0000-4000-8000-000000000001', 'Driver D');
insert into transport_route (id, tenant_id, campus_id, name, driver_name) values
  ('1c000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Route T2', 'Driver E');
insert into route_stop (id, tenant_id, route_id, sequence, name, pickup_time) values
  ('1c000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', 1, 'Stop A', '07:10'),
  ('1c000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000003', 1, 'Stop B', '07:20');
insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001');
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: a stop must be on the route; students must be enrolled; only one active assignment a year.
do $$ begin
  begin insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values
    ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020',
     '1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000005');
    raise exception 'T1 failed: stop of another route accepted';
  exception when check_violation then null; end;
  begin insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values
    ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004',
     '1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000004');
    raise exception 'T1 failed: non-student assigned';
  exception when check_violation then null; end;
  insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values
    ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020',
     '1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000004');
  begin insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values
    ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020',
     '1c000000-0000-4000-8000-000000000003', '1c000000-0000-4000-8000-000000000005');
    raise exception 'T1 failed: two active routes';
  exception when unique_violation then null; end;
  raise notice 'T1 pass: assignments are consistent';
end $$;

-- T2: a one-seat vehicle takes one child.
do $$ begin
  begin insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values
    ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021',
     '1c000000-0000-4000-8000-000000000002', '1c000000-0000-4000-8000-000000000004');
    raise exception 'T2 failed: over capacity';
  exception when check_violation then if sqlerrm <> 'route full' then raise; end if; end;
  raise notice 'T2 pass: capacity holds';
end $$;

-- T3: boarding is only for assigned students, not in the future, once per trip, and permanent.
do $$ begin
  insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020',
     current_date, 'MORNING', 'BOARDED', '13000000-0000-4000-8000-000000000002');
  begin insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020',
     current_date, 'MORNING', 'BOARDED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: duplicate accepted';
  exception when unique_violation then null; end;
  begin insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000003', '13000000-0000-4000-8000-000000000020',
     current_date, 'MORNING', 'BOARDED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: unassigned route';
  exception when check_violation then null; end;
  begin insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020',
     current_date + 2, 'AFTERNOON', 'BOARDED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T3 failed: future trip';
  exception when check_violation then null; end;
  begin update boarding_event set trip = 'AFTERNOON'; raise exception 'T3 failed: edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from boarding_event; raise exception 'T3 failed: deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'T3 pass: boarding log is controlled and permanent';
end $$;

-- T4: an assignment is ended, not edited or removed.
do $$ begin
  begin update transport_assignment set route_id = '1c000000-0000-4000-8000-000000000003'; raise exception 'T4 failed: edited';
  exception when check_violation or insufficient_privilege then null; end;
  update transport_assignment set status = 'ENDED', ended_on = current_date;
  begin update transport_assignment set status = 'ACTIVE', ended_on = null; raise exception 'T4 failed: reopened';
  exception when check_violation then null; end;
  begin insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by) values
    ('10000000-0000-4000-8000-000000000001', '1c000000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000020',
     current_date, 'AFTERNOON', 'BOARDED', '13000000-0000-4000-8000-000000000002');
    raise exception 'T4 failed: boarding after the assignment ended';
  exception when check_violation then null; end;
  raise notice 'T4 pass: assignments end cleanly';
end $$;

-- T5: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from transport_route) + (select count(*) from transport_assignment) + (select count(*) from boarding_event) <> 0 then
    raise exception 'T5 failed: cross-tenant read'; end if;
  raise notice 'T5 pass: tenant isolation';
end $$;
rollback;

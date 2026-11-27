-- B22 facilities. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into room (id, tenant_id, campus_id, code, name, capacity) values
  ('1f000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'ZZ-FAC', 'Facility lab', 30);
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- T1: bookings never overlap, touching ones are fine, cancelled ones free the slot.
do $$ begin
  insert into room_booking (id, tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('1f000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001',
     '13000000-0000-4000-8000-000000000002', 'Physics practical', now() + interval '1 day', now() + interval '1 day 2 hours');
  begin insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Clash',
     now() + interval '1 day 1 hour', now() + interval '1 day 3 hours');
    raise exception 'T1 failed: overlapping booking';
  exception when check_violation then if sqlerrm <> 'room already booked' then raise; end if; end;
  insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Right after',
     now() + interval '1 day 2 hours', now() + interval '1 day 3 hours');
  update room_booking set status = 'CANCELLED' where id = '1f000000-0000-4000-8000-000000000002';
  insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Reuse',
     now() + interval '1 day', now() + interval '1 day 1 hour');
  raise notice 'T1 pass: no double booking';
end $$;

-- T2: bookings are sane and permanent.
do $$ begin
  begin insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Yesterday',
     now() - interval '1 day', now() - interval '23 hours');
    raise exception 'T2 failed: booking in the past';
  exception when check_violation then null; end;
  begin insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Too long',
     now() + interval '5 days', now() + interval '6 days');
    raise exception 'T2 failed: day-long booking';
  exception when check_violation then null; end;
  begin update room_booking set status = 'BOOKED' where id = '1f000000-0000-4000-8000-000000000002';
    raise exception 'T2 failed: cancelled booking came back';
  exception when check_violation then null; end;
  raise notice 'T2 pass: bookings are sane';
end $$;

-- T3: a retired room takes no bookings.
do $$ begin
  update room set status = 'RETIRED' where id = '1f000000-0000-4000-8000-000000000001';
  begin insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values
    ('10000000-0000-4000-8000-000000000001', '1f000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'Nope',
     now() + interval '9 days', now() + interval '9 days 1 hour');
    raise exception 'T3 failed: booked a retired room';
  exception when check_violation then null; end;
  raise notice 'T3 pass: retirement holds';
end $$;

-- T4: maintenance moves forward only, and resolving needs a note.
do $$ declare rid uuid; begin
  insert into maintenance_request (tenant_id, campus_id, title, reported_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'Leaking tap', '13000000-0000-4000-8000-000000000002') returning id into rid;
  begin update maintenance_request set status = 'RESOLVED', resolution = 'Fixed', resolved_at = now() where id = rid;
    raise exception 'T4 failed: skipped a step';
  exception when check_violation then null; end;
  update maintenance_request set status = 'IN_PROGRESS' where id = rid;
  begin update maintenance_request set status = 'RESOLVED' where id = rid;
    raise exception 'T4 failed: resolved without a note';
  exception when check_violation then null; end;
  update maintenance_request set status = 'RESOLVED', resolution = 'Washer replaced', resolved_at = now() where id = rid;
  begin update maintenance_request set status = 'OPEN', resolution = null, resolved_at = null where id = rid;
    raise exception 'T4 failed: reopened';
  exception when check_violation then null; end;
  begin update maintenance_request set resolution = 'Changed my mind' where id = rid;
    raise exception 'T4 failed: resolution edited';
  exception when check_violation then null; end;
  raise notice 'T4 pass: maintenance lifecycle holds';
end $$;

-- T5: other tenants see nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from room_booking) + (select count(*) from maintenance_request) <> 0 then raise exception 'T5 failed: leak'; end if;
  raise notice 'T5 pass: tenants are isolated';
end $$;
rollback;

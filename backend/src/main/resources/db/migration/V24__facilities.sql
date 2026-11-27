-- B22 / M21 facilities. Bookings of the existing rooms that can never overlap, and maintenance requests that move
-- OPEN -> IN_PROGRESS -> RESOLVED. Visitor management is not part of this slice.

-- Rooms already exist (V8, used by the timetable); bookings and requests reference them.

create table room_booking (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references tenant(id),
  room_id    uuid not null,
  booked_by  uuid not null,
  purpose    text not null check (char_length(btrim(purpose)) between 2 and 200),
  starts_at  timestamptz not null,
  ends_at    timestamptz not null,
  status     text not null default 'BOOKED' check (status in ('BOOKED','CANCELLED')),
  created_at timestamptz not null default clock_timestamp(),
  unique (tenant_id, id),
  foreign key (tenant_id, room_id) references room(tenant_id, id),
  foreign key (tenant_id, booked_by) references person(tenant_id, id),
  check (ends_at > starts_at and ends_at - starts_at <= interval '12 hours')
);
create index room_booking_by_room on room_booking (tenant_id, room_id, starts_at);

create table maintenance_request (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  campus_id   uuid not null,
  room_id     uuid,
  title       text not null check (char_length(btrim(title)) between 3 and 120),
  description text check (description is null or char_length(description) <= 2000),
  priority    text not null default 'NORMAL' check (priority in ('LOW','NORMAL','URGENT')),
  status      text not null default 'OPEN' check (status in ('OPEN','IN_PROGRESS','RESOLVED')),
  reported_by uuid not null,
  reported_at timestamptz not null default clock_timestamp(),
  resolution  text check (resolution is null or char_length(btrim(resolution)) between 2 and 1000),
  resolved_at timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, room_id) references room(tenant_id, id),
  foreign key (tenant_id, reported_by) references person(tenant_id, id),
  check ((status = 'RESOLVED') = (resolution is not null and resolved_at is not null))
);

create function facility_guard() returns trigger language plpgsql as $$
declare r_status text; r_campus uuid;
begin
  if tg_table_name = 'room_booking' then
    if tg_op = 'DELETE' then raise exception 'bookings are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.room_id, new.booked_by, new.purpose, new.starts_at, new.ends_at) is distinct from (old.room_id, old.booked_by, old.purpose, old.starts_at, old.ends_at) then
        raise exception 'a booking cannot be edited, cancel it and book again' using errcode = 'check_violation'; end if;
      if old.status = 'CANCELLED' and new.status <> 'CANCELLED' then raise exception 'a cancelled booking stays cancelled' using errcode = 'check_violation'; end if;
      return new;
    end if;
    select status into r_status from room where tenant_id = new.tenant_id and id = new.room_id;
    if r_status <> 'ACTIVE' then raise exception 'the room is retired' using errcode = 'check_violation'; end if;
    if new.starts_at < now() - interval '5 minutes' then raise exception 'a booking cannot start in the past' using errcode = 'check_violation'; end if;
    perform pg_advisory_xact_lock(hashtext(new.room_id::text));
    if exists (select 1 from room_booking b where b.tenant_id = new.tenant_id and b.room_id = new.room_id and b.status = 'BOOKED'
                  and b.starts_at < new.ends_at and b.ends_at > new.starts_at) then
      raise exception 'room already booked' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  -- maintenance_request
  if tg_op = 'DELETE' then raise exception 'requests are kept' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' then
    if new.room_id is not null then
      select campus_id into r_campus from room where tenant_id = new.tenant_id and id = new.room_id;
      if r_campus is distinct from new.campus_id then raise exception 'the room is on another campus' using errcode = 'check_violation'; end if;
    end if;
    if new.status <> 'OPEN' then raise exception 'a request starts open' using errcode = 'check_violation'; end if;
    return new;
  end if;
  if (new.campus_id, new.room_id, new.title, new.description, new.priority, new.reported_by, new.reported_at)
     is distinct from (old.campus_id, old.room_id, old.title, old.description, old.priority, old.reported_by, old.reported_at) then
    raise exception 'a request cannot be edited' using errcode = 'check_violation';
  end if;
  if new.status <> old.status and not ((old.status, new.status) in (('OPEN','IN_PROGRESS'), ('IN_PROGRESS','RESOLVED'))) then
    raise exception 'a request cannot move from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if old.status = 'RESOLVED' and (new.resolution, new.resolved_at) is distinct from (old.resolution, old.resolved_at) then
    raise exception 'a resolved request stays as it is' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger room_booking_check before insert or update or delete on room_booking for each row execute function facility_guard();
create trigger maintenance_request_check before insert or update or delete on maintenance_request for each row execute function facility_guard();

do $$
declare t text;
begin
  foreach t in array array['room_booking','maintenance_request']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status) on room_booking to schoomano_app;
grant select, insert, update (status, resolution, resolved_at) on maintenance_request to schoomano_app;

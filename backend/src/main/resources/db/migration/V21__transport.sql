-- B19 / M17 transport. Vehicles, routes with ordered stops, student assignments within vehicle capacity, and an append-only
-- log of boarding and alighting. The log is idempotent per student, route, day, trip and event so a retried phone is harmless.

create table vehicle (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  campus_id    uuid not null,
  registration text not null check (char_length(btrim(registration)) between 4 and 20),
  capacity     int not null check (capacity between 1 and 100),
  active       boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, registration),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

create table transport_route (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  campus_id    uuid not null,
  name         text not null check (char_length(btrim(name)) between 2 and 80),
  vehicle_id   uuid,
  driver_name  text not null check (char_length(btrim(driver_name)) between 2 and 80),
  driver_phone text check (driver_phone is null or driver_phone ~ '^\+?[0-9]{8,15}$'),
  active       boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, campus_id, name),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, vehicle_id) references vehicle(tenant_id, id)
);

create table route_stop (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  route_id    uuid not null,
  sequence    int not null check (sequence > 0),
  name        text not null check (char_length(btrim(name)) between 2 and 80),
  pickup_time time not null,
  unique (tenant_id, id),
  unique (tenant_id, route_id, sequence),
  foreign key (tenant_id, route_id) references transport_route(tenant_id, id)
);

create table transport_assignment (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  academic_year_id  uuid not null,
  student_person_id uuid not null,
  route_id          uuid not null,
  stop_id           uuid not null,
  status            text not null default 'ACTIVE' check (status in ('ACTIVE','ENDED')),
  started_on        date not null default current_date,
  ended_on          date,
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, route_id) references transport_route(tenant_id, id),
  foreign key (tenant_id, stop_id) references route_stop(tenant_id, id),
  check ((status = 'ENDED') = (ended_on is not null))
);
create unique index transport_one_active on transport_assignment (tenant_id, academic_year_id, student_person_id) where status = 'ACTIVE';

create table boarding_event (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  route_id          uuid not null,
  student_person_id uuid not null,
  trip_date         date not null,
  trip              text not null check (trip in ('MORNING','AFTERNOON')),
  event             text not null check (event in ('BOARDED','ALIGHTED')),
  recorded_by       uuid not null,
  recorded_at       timestamptz not null default now(),
  unique (tenant_id, route_id, student_person_id, trip_date, trip, event),
  foreign key (tenant_id, route_id) references transport_route(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create function transport_guard() returns trigger language plpgsql as $$
declare cap int; taken int; stop_route uuid; r_active boolean;
begin
  if tg_table_name = 'transport_assignment' then
    if tg_op = 'DELETE' then raise exception 'assignments are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.student_person_id, new.route_id, new.stop_id, new.academic_year_id, new.started_on)
         is distinct from (old.student_person_id, old.route_id, old.stop_id, old.academic_year_id, old.started_on) then
        raise exception 'an assignment cannot be edited, end it and make a new one' using errcode = 'check_violation';
      end if;
      if old.status = 'ENDED' and new.status <> 'ENDED' then
        raise exception 'an ended assignment stays ended' using errcode = 'check_violation';
      end if;
      return new;
    end if;
    select route_id into stop_route from route_stop where tenant_id = new.tenant_id and id = new.stop_id;
    if stop_route is distinct from new.route_id then
      raise exception 'the stop is not on that route' using errcode = 'check_violation';
    end if;
    select active, (select capacity from vehicle v where v.tenant_id = r.tenant_id and v.id = r.vehicle_id)
      into r_active, cap from transport_route r where r.tenant_id = new.tenant_id and r.id = new.route_id;
    if not r_active then raise exception 'the route is not running' using errcode = 'check_violation'; end if;
    if not exists (select 1 from student_enrollment e where e.student_person_id = new.student_person_id and e.academic_year_id = new.academic_year_id) then
      raise exception 'the student is not enrolled in that year' using errcode = 'check_violation';
    end if;
    if cap is not null then
      perform pg_advisory_xact_lock(hashtext(new.route_id::text));
      select count(*) into taken from transport_assignment a
       where a.tenant_id = new.tenant_id and a.route_id = new.route_id and a.status = 'ACTIVE';
      if taken >= cap then raise exception 'route full' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  if tg_table_name = 'transport_route' and tg_op = 'UPDATE' and new.vehicle_id is distinct from old.vehicle_id and new.vehicle_id is not null then
    select capacity into cap from vehicle where tenant_id = new.tenant_id and id = new.vehicle_id;
    select count(*) into taken from transport_assignment a where a.tenant_id = new.tenant_id and a.route_id = new.id and a.status = 'ACTIVE';
    if taken > cap then raise exception 'the vehicle is too small for the students on this route' using errcode = 'check_violation'; end if;
  end if;
  return new;
end $$;
create trigger transport_assignment_check before insert or update or delete on transport_assignment
  for each row execute function transport_guard();
create trigger transport_route_check before update on transport_route
  for each row execute function transport_guard();

create function boarding_guard() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then raise exception 'boarding records are permanent' using errcode = 'check_violation'; end if;
  if new.trip_date > (now() at time zone 'Asia/Kolkata')::date then
    raise exception 'a trip cannot be in the future' using errcode = 'check_violation';
  end if;
  if not exists (select 1 from transport_assignment a where a.tenant_id = new.tenant_id and a.route_id = new.route_id
                    and a.student_person_id = new.student_person_id and a.status = 'ACTIVE') then
    raise exception 'the student is not assigned to this route' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger boarding_event_check before insert or update or delete on boarding_event
  for each row execute function boarding_guard();

do $$
declare t text;
begin
  foreach t in array array['vehicle','transport_route','route_stop','transport_assignment','boarding_event']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on vehicle, transport_route to schoomano_app;
grant select, insert on route_stop, boarding_event to schoomano_app;
grant select, insert, update (status, ended_on) on transport_assignment to schoomano_app;

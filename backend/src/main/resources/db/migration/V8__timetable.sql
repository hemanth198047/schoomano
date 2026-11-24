-- B5 Timetable and substitutions (M05).
--
-- A weekly timetable per class section for an academic year. While a year's timetable is a DRAFT anything may be
-- entered, including teacher or room double-bookings (the office sees them listed and fixes them). PUBLISHING is
-- refused while any clash exists, and once published the database itself refuses any change that would create one,
-- so a published timetable can never be double-booked, whatever code path edits it. A class section can only have one
-- lesson per day and period by construction (unique key), so there is no "section clash" to publish around.
-- Substitutions cover a teacher's absence for one date and are checked the same way.

-- The bell schedule of a campus: ordered periods (and breaks) with start and end times.
create table period (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  campus_id   uuid not null,
  ordinal     integer not null check (ordinal between 1 and 12),
  label       text not null check (char_length(label) between 1 and 40),
  starts_at   time not null,
  ends_at     time not null,
  is_break    boolean not null default false,
  created_at  timestamptz not null default now(),
  check (ends_at > starts_at),
  unique (tenant_id, campus_id, ordinal),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

create table room (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  campus_id   uuid not null,
  code        text not null check (code ~ '^[A-Z0-9-]{1,12}$'),
  name        text not null check (char_length(name) between 1 and 80),
  capacity    integer check (capacity is null or capacity between 1 and 500),
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  created_at  timestamptz not null default now(),
  unique (tenant_id, campus_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

-- One row per academic year once it has been published (absent = still a draft).
create table timetable_publication (
  tenant_id        uuid not null references tenant(id),
  academic_year_id uuid not null,
  status           text not null check (status in ('DRAFT','PUBLISHED')),
  version          integer not null default 0,
  published_at     timestamptz,
  published_by     uuid,
  primary key (tenant_id, academic_year_id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

create table timetable_entry (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  academic_year_id  uuid not null,
  class_section_id  uuid not null,
  day_of_week       integer not null check (day_of_week between 1 and 7),   -- ISO: Monday = 1
  period_id         uuid not null,
  subject_id        uuid not null,
  teacher_person_id uuid not null,
  room_id           uuid,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz,
  unique (tenant_id, class_section_id, day_of_week, period_id),
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id),
  foreign key (tenant_id, period_id) references period(tenant_id, id),
  foreign key (tenant_id, subject_id) references subject(tenant_id, id),
  foreign key (tenant_id, teacher_person_id) references person(tenant_id, id),
  foreign key (tenant_id, room_id) references room(tenant_id, id)
);
create index timetable_entry_teacher_idx on timetable_entry (tenant_id, academic_year_id, teacher_person_id, day_of_week, period_id);
create index timetable_entry_room_idx on timetable_entry (tenant_id, academic_year_id, room_id, day_of_week, period_id) where room_id is not null;

create table substitution (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references tenant(id),
  timetable_entry_id    uuid not null,
  on_date               date not null,
  substitute_person_id  uuid not null,
  reason                text not null check (char_length(reason) between 3 and 200),
  created_by            uuid,
  created_at            timestamptz not null default now(),
  unique (tenant_id, timetable_entry_id, on_date),
  foreign key (tenant_id, timetable_entry_id) references timetable_entry(tenant_id, id) on delete cascade,
  foreign key (tenant_id, substitute_person_id) references person(tenant_id, id)
);
create index substitution_date_idx on substitution (tenant_id, on_date);

-- ---------------------------------------------------------------------------------------------------------
-- Clashes in one academic year: the same teacher or the same room in two lessons at the same day and period.
create or replace function timetable_clashes(p_year uuid)
  returns table (kind text, day_of_week integer, period_id uuid, resource_id uuid, entry_ids uuid[])
  language sql stable as $$
  select 'TEACHER', e.day_of_week, e.period_id, e.teacher_person_id, array_agg(e.id order by e.id)
    from timetable_entry e where e.academic_year_id = p_year
   group by e.day_of_week, e.period_id, e.teacher_person_id having count(*) > 1
  union all
  select 'ROOM', e.day_of_week, e.period_id, e.room_id, array_agg(e.id order by e.id)
    from timetable_entry e where e.academic_year_id = p_year and e.room_id is not null
   group by e.day_of_week, e.period_id, e.room_id having count(*) > 1
$$;

-- The bell schedule never overlaps itself (breaks included).
create or replace function period_no_overlap() returns trigger language plpgsql as $$
begin
  if exists (select 1 from period p
              where p.campus_id = new.campus_id and p.tenant_id = new.tenant_id and p.id <> new.id
                and p.starts_at < new.ends_at and new.starts_at < p.ends_at) then
    raise exception 'period overlaps another period of this campus' using errcode = 'check_violation';
  end if;
  -- ordinals follow the clock: a later ordinal never starts before an earlier one
  if exists (select 1 from period p
              where p.campus_id = new.campus_id and p.tenant_id = new.tenant_id and p.id <> new.id
                and ((p.ordinal < new.ordinal and p.starts_at > new.starts_at)
                  or (p.ordinal > new.ordinal and p.starts_at < new.starts_at))) then
    raise exception 'period order does not follow the clock' using errcode = 'check_violation';
  end if;
  return null;
end $$;
-- Deferrable so the API can reshuffle several periods in one transaction (it defers this check, then it runs at commit).
create constraint trigger period_no_overlap after insert or update on period
  deferrable initially immediate for each row execute function period_no_overlap();

-- Lessons are consistent with the academic structure, and (once published) never double-book.
create or replace function timetable_entry_guard() returns trigger language plpgsql as $$
declare
  y academic_year%rowtype;
  sec_year uuid; sec_programme uuid; sub_programme uuid; sub_status text;
  per_campus uuid; per_break boolean; room_campus uuid; room_status text;
begin
  select * into y from academic_year where id = new.academic_year_id;
  if y.status = 'CLOSED' then
    raise exception 'the academic year is closed' using errcode = 'check_violation';
  end if;

  select s.academic_year_id, g.programme_id into sec_year, sec_programme
    from class_section s join grade_level g on g.id = s.grade_level_id where s.id = new.class_section_id;
  if sec_year is distinct from new.academic_year_id then
    raise exception 'section belongs to a different academic year' using errcode = 'check_violation';
  end if;

  select programme_id, status into sub_programme, sub_status from subject where id = new.subject_id;
  if sub_programme is distinct from sec_programme then
    raise exception 'subject belongs to a different programme than the section' using errcode = 'check_violation';
  end if;
  if sub_status <> 'ACTIVE' then
    raise exception 'subject is retired' using errcode = 'check_violation';
  end if;

  select campus_id, is_break into per_campus, per_break from period where id = new.period_id;
  if per_campus is distinct from y.campus_id then
    raise exception 'period belongs to a different campus' using errcode = 'check_violation';
  end if;
  if per_break then
    raise exception 'lessons cannot be placed in a break' using errcode = 'check_violation';
  end if;

  if new.room_id is not null then
    select campus_id, status into room_campus, room_status from room where id = new.room_id;
    if room_campus is distinct from y.campus_id then
      raise exception 'room belongs to a different campus' using errcode = 'check_violation';
    end if;
    if room_status <> 'ACTIVE' then
      raise exception 'room is retired' using errcode = 'check_violation';
    end if;
  end if;

  if not exists (select 1 from role_grant r
                  where r.person_id = new.teacher_person_id and r.role_key = 'teacher'
                    and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now())) then
    raise exception 'the teacher does not hold the teacher role' using errcode = 'check_violation';
  end if;

  -- After publication a change may not create a double booking.
  if exists (select 1 from timetable_publication p
              where p.academic_year_id = new.academic_year_id and p.status = 'PUBLISHED') then
    if exists (select 1 from timetable_entry e
                where e.academic_year_id = new.academic_year_id and e.id <> new.id
                  and e.day_of_week = new.day_of_week and e.period_id = new.period_id
                  and e.teacher_person_id = new.teacher_person_id) then
      raise exception 'teacher is already teaching at that time' using errcode = 'unique_violation';
    end if;
    if new.room_id is not null and exists (select 1 from timetable_entry e
                where e.academic_year_id = new.academic_year_id and e.id <> new.id
                  and e.day_of_week = new.day_of_week and e.period_id = new.period_id
                  and e.room_id = new.room_id) then
      raise exception 'room is already in use at that time' using errcode = 'unique_violation';
    end if;
  end if;
  new.updated_at = case when tg_op = 'UPDATE' then now() else null end;
  return new;
end $$;
create trigger timetable_entry_guard before insert or update on timetable_entry
  for each row execute function timetable_entry_guard();

-- Publishing is refused while clashes exist (the API lists them first; this is the backstop).
create or replace function timetable_publication_guard() returns trigger language plpgsql as $$
begin
  if new.status = 'PUBLISHED' and exists (select 1 from timetable_clashes(new.academic_year_id)) then
    raise exception 'the timetable still has clashes' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger timetable_publication_guard before insert or update on timetable_publication
  for each row execute function timetable_publication_guard();

-- Substitutions: only on a published timetable, on the right weekday inside the year, by a different teacher who
-- is free at that time (not teaching then, unless their own lesson is itself covered that day, and not already
-- covering another class).
create or replace function substitution_guard() returns trigger language plpgsql as $$
declare e timetable_entry%rowtype; y academic_year%rowtype;
begin
  select * into e from timetable_entry where id = new.timetable_entry_id;
  select * into y from academic_year where id = e.academic_year_id;
  if not exists (select 1 from timetable_publication p where p.academic_year_id = e.academic_year_id and p.status = 'PUBLISHED') then
    raise exception 'the timetable is not published' using errcode = 'check_violation';
  end if;
  if new.on_date < y.starts_on or new.on_date > y.ends_on then
    raise exception 'date is outside the academic year' using errcode = 'check_violation';
  end if;
  if extract(isodow from new.on_date)::int <> e.day_of_week then
    raise exception 'date is not a % lesson day', e.day_of_week using errcode = 'check_violation';
  end if;
  if new.substitute_person_id = e.teacher_person_id then
    raise exception 'the substitute is the teacher being replaced' using errcode = 'check_violation';
  end if;
  if not exists (select 1 from role_grant r
                  where r.person_id = new.substitute_person_id and r.role_key = 'teacher'
                    and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now())) then
    raise exception 'the substitute does not hold the teacher role' using errcode = 'check_violation';
  end if;
  if exists (select 1 from timetable_entry o
              where o.academic_year_id = e.academic_year_id and o.day_of_week = e.day_of_week and o.period_id = e.period_id
                and o.teacher_person_id = new.substitute_person_id
                and not exists (select 1 from substitution s where s.timetable_entry_id = o.id and s.on_date = new.on_date)) then
    raise exception 'the substitute is teaching at that time' using errcode = 'unique_violation';
  end if;
  if exists (select 1 from substitution s join timetable_entry o on o.id = s.timetable_entry_id
              where s.on_date = new.on_date and s.substitute_person_id = new.substitute_person_id and s.id <> new.id
                and o.day_of_week = e.day_of_week and o.period_id = e.period_id) then
    raise exception 'the substitute is already covering another class then' using errcode = 'unique_violation';
  end if;
  return new;
end $$;
create trigger substitution_guard before insert or update on substitution
  for each row execute function substitution_guard();

-- Tenant isolation and least privilege, exactly as V2/V3.
do $$
declare t text;
begin
  foreach t in array array['period','room','timetable_publication','timetable_entry','substitution']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update, delete on period, room, timetable_publication, timetable_entry, substitution to schoomano_app;

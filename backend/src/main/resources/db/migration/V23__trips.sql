-- B21 / M19 trips and events. A trip has a date, cost, consent deadline and an optional seat limit; staff add the students
-- who are invited; a guardian with the consent capability gives or declines consent for their child until the deadline
-- (every decision is kept, the latest one counts); on the day staff record departure and return headcounts for children
-- whose latest consent is "given".

create table trip (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  campus_id        uuid not null,
  academic_year_id uuid not null,
  name             text not null check (char_length(btrim(name)) between 2 and 120),
  destination      text not null check (char_length(btrim(destination)) between 2 and 200),
  trip_date        date not null,
  cost_paise       bigint not null default 0 check (cost_paise >= 0),
  consent_deadline date not null,
  capacity         int check (capacity is null or capacity between 1 and 1000),
  status           text not null default 'PLANNED' check (status in ('PLANNED','COMPLETED','CANCELLED')),
  created_by       uuid not null,
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  check (consent_deadline <= trip_date)
);

create table trip_participant (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  trip_id           uuid not null,
  student_person_id uuid not null,
  status            text not null default 'INVITED' check (status in ('INVITED','REMOVED')),
  unique (tenant_id, id),
  unique (tenant_id, trip_id, student_person_id),
  foreign key (tenant_id, trip_id) references trip(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create table trip_consent (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references tenant(id),
  trip_id            uuid not null,
  student_person_id  uuid not null,
  guardian_person_id uuid not null,
  decision           text not null check (decision in ('GIVEN','DECLINED')),
  note               text check (note is null or char_length(note) <= 500),
  decided_at         timestamptz not null default clock_timestamp(),
  foreign key (tenant_id, trip_id) references trip(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, guardian_person_id) references person(tenant_id, id)
);
create index trip_consent_latest on trip_consent (tenant_id, trip_id, student_person_id, decided_at desc);

create table trip_headcount (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  trip_id           uuid not null,
  student_person_id uuid not null,
  checkpoint        text not null check (checkpoint in ('DEPARTED','RETURNED')),
  recorded_by       uuid not null,
  recorded_at       timestamptz not null default now(),
  unique (tenant_id, trip_id, student_person_id, checkpoint),
  foreign key (tenant_id, trip_id) references trip(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create function trip_guard() returns trigger language plpgsql as $$
declare t record; cap int; taken int; latest text; today date := (now() at time zone 'Asia/Kolkata')::date;
begin
  if tg_table_name = 'trip' then
    if tg_op = 'DELETE' then raise exception 'trips are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.campus_id, new.academic_year_id, new.name, new.destination, new.trip_date, new.cost_paise, new.consent_deadline, new.capacity)
         is distinct from (old.campus_id, old.academic_year_id, old.name, old.destination, old.trip_date, old.cost_paise, old.consent_deadline, old.capacity) then
        raise exception 'a trip cannot be edited, cancel it and plan a new one' using errcode = 'check_violation';
      end if;
      if old.status <> 'PLANNED' and new.status <> old.status then raise exception 'a finished trip stays as it is' using errcode = 'check_violation'; end if;
      if new.status = 'COMPLETED' and new.trip_date > today then raise exception 'a trip cannot finish before its day' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  select * into t from trip where tenant_id = new.tenant_id and id = new.trip_id;
  if tg_table_name = 'trip_participant' then
    if tg_op = 'DELETE' then raise exception 'the trip list is kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.trip_id, new.student_person_id) is distinct from (old.trip_id, old.student_person_id) then
        raise exception 'the trip list cannot be edited' using errcode = 'check_violation'; end if;
      if old.status = 'REMOVED' and new.status <> 'REMOVED' then raise exception 'a removed student stays removed' using errcode = 'check_violation'; end if;
      return new;
    end if;
    if t.status <> 'PLANNED' then raise exception 'the trip is not open' using errcode = 'check_violation'; end if;
    if not exists (select 1 from student_enrollment e where e.student_person_id = new.student_person_id and e.academic_year_id = t.academic_year_id) then
      raise exception 'the student is not enrolled in that year' using errcode = 'check_violation';
    end if;
    if t.capacity is not null then
      perform pg_advisory_xact_lock(hashtext(new.trip_id::text));
      select count(*) into taken from trip_participant p where p.tenant_id = new.tenant_id and p.trip_id = new.trip_id and p.status = 'INVITED';
      if taken >= t.capacity then raise exception 'trip full' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  if tg_table_name = 'trip_consent' then
    if tg_op <> 'INSERT' then raise exception 'consent decisions are permanent' using errcode = 'check_violation'; end if;
    if t.status <> 'PLANNED' then raise exception 'the trip is not open' using errcode = 'check_violation'; end if;
    if today > t.consent_deadline then raise exception 'the consent deadline has passed' using errcode = 'check_violation'; end if;
    if not exists (select 1 from trip_participant p where p.tenant_id = new.tenant_id and p.trip_id = new.trip_id
                      and p.student_person_id = new.student_person_id and p.status = 'INVITED') then
      raise exception 'the student is not on this trip' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  -- trip_headcount
  if tg_op <> 'INSERT' then raise exception 'headcounts are permanent' using errcode = 'check_violation'; end if;
  if t.status = 'CANCELLED' then raise exception 'the trip is cancelled' using errcode = 'check_violation'; end if;
  if t.trip_date > today then raise exception 'the trip has not started' using errcode = 'check_violation'; end if;
  select decision into latest from trip_consent c where c.tenant_id = new.tenant_id and c.trip_id = new.trip_id
     and c.student_person_id = new.student_person_id order by decided_at desc, id limit 1;
  if latest is distinct from 'GIVEN' then raise exception 'there is no consent for this student' using errcode = 'check_violation'; end if;
  if new.checkpoint = 'RETURNED' and not exists (select 1 from trip_headcount h where h.tenant_id = new.tenant_id and h.trip_id = new.trip_id
       and h.student_person_id = new.student_person_id and h.checkpoint = 'DEPARTED') then
    raise exception 'the student never departed' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger trip_check before insert or update or delete on trip for each row execute function trip_guard();
create trigger trip_participant_check before insert or update or delete on trip_participant for each row execute function trip_guard();
create trigger trip_consent_check before insert or update or delete on trip_consent for each row execute function trip_guard();
create trigger trip_headcount_check before insert or update or delete on trip_headcount for each row execute function trip_guard();

do $$
declare t text;
begin
  foreach t in array array['trip','trip_participant','trip_consent','trip_headcount']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status) on trip to schoomano_app;
grant select, insert, update (status) on trip_participant to schoomano_app;
grant select, insert on trip_consent, trip_headcount to schoomano_app;

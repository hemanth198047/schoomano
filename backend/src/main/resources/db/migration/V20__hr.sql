-- B18 / M16 HR and workforce. A staff profile per person and campus, leave allowances per campus, year and type, and leave
-- requests that someone else approves within the balance. Leave dates and days cannot be edited once requested.

create table staff_profile (
  tenant_id       uuid not null references tenant(id),
  person_id       uuid not null,
  campus_id       uuid not null,
  employee_no     text not null check (char_length(btrim(employee_no)) between 2 and 30),
  designation     text not null check (char_length(btrim(designation)) between 2 and 80),
  department      text check (department is null or char_length(department) <= 80),
  employment_type text not null check (employment_type in ('FULL_TIME','PART_TIME','CONTRACT')),
  joined_on       date not null,
  status          text not null default 'ACTIVE' check (status in ('ACTIVE','LEFT')),
  left_on         date,
  updated_at      timestamptz not null default now(),
  primary key (tenant_id, person_id),
  unique (tenant_id, employee_no),
  foreign key (tenant_id, person_id) references person(tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  check ((status = 'LEFT') = (left_on is not null)),
  check (left_on is null or left_on >= joined_on)
);

create table leave_allowance (
  tenant_id        uuid not null references tenant(id),
  campus_id        uuid not null,
  academic_year_id uuid not null,
  leave_type       text not null check (leave_type in ('CASUAL','SICK','EARNED')),
  days             numeric(5,1) not null check (days >= 0 and days <= 366),
  primary key (tenant_id, campus_id, academic_year_id, leave_type),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

create table leave_request (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  person_id        uuid not null,
  campus_id        uuid not null,
  academic_year_id uuid not null,
  leave_type       text not null check (leave_type in ('CASUAL','SICK','EARNED','UNPAID')),
  from_date        date not null,
  to_date          date not null,
  days             numeric(5,1) not null check (days > 0),
  reason           text check (reason is null or char_length(reason) <= 1000),
  status           text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','CANCELLED')),
  decided_by       uuid,
  decision_note    text check (decision_note is null or char_length(decision_note) <= 1000),
  requested_at     timestamptz not null default now(),
  decided_at       timestamptz,
  check (to_date >= from_date),
  unique (tenant_id, id),
  foreign key (tenant_id, person_id) references staff_profile(tenant_id, person_id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);
create index leave_request_by_person on leave_request (tenant_id, person_id, academic_year_id, status);

create function hr_guard() returns trigger language plpgsql as $$
declare allowance numeric; used numeric;
begin
  if tg_table_name = 'staff_profile' then
    if tg_op = 'DELETE' then raise exception 'staff records are not deleted' using errcode = 'check_violation'; end if;
    if exists (select 1 from student_enrollment where student_person_id = new.person_id) then
      raise exception 'a student cannot have a staff profile' using errcode = 'check_violation';
    end if;
    new.updated_at := now();
    return new;
  end if;
  -- leave_request
  if tg_op = 'DELETE' then raise exception 'leave requests are permanent' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' then
    if exists (select 1 from leave_request l where l.tenant_id = new.tenant_id and l.person_id = new.person_id
                  and l.status in ('PENDING','APPROVED') and l.from_date <= new.to_date and l.to_date >= new.from_date) then
      raise exception 'overlapping leave' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if (new.person_id, new.leave_type, new.from_date, new.to_date, new.days, new.campus_id, new.academic_year_id)
     is distinct from (old.person_id, old.leave_type, old.from_date, old.to_date, old.days, old.campus_id, old.academic_year_id) then
    raise exception 'a leave request cannot be edited' using errcode = 'check_violation';
  end if;
  if new.status = old.status then return new; end if;
  if old.status <> 'PENDING' then
    raise exception 'a decided request is final' using errcode = 'check_violation';
  end if;
  if new.status = 'APPROVED' then
    if new.decided_by is null or new.decided_by = new.person_id then
      raise exception 'leave must be approved by someone else' using errcode = 'check_violation';
    end if;
    if new.leave_type <> 'UNPAID' then
      perform pg_advisory_xact_lock(hashtext(new.person_id::text || new.academic_year_id::text));
      select coalesce(max(a.days), 0) into allowance from leave_allowance a
       where a.tenant_id = new.tenant_id and a.campus_id = new.campus_id and a.academic_year_id = new.academic_year_id and a.leave_type = new.leave_type;
      select coalesce(sum(l.days), 0) into used from leave_request l
       where l.tenant_id = new.tenant_id and l.person_id = new.person_id and l.academic_year_id = new.academic_year_id
         and l.leave_type = new.leave_type and l.status = 'APPROVED' and l.id <> new.id;
      if used + new.days > allowance then
        raise exception 'insufficient leave balance' using errcode = 'check_violation';
      end if;
    end if;
  end if;
  new.decided_at := now();
  return new;
end $$;
create trigger staff_profile_check before insert or update or delete on staff_profile
  for each row execute function hr_guard();
create trigger leave_request_check before insert or update or delete on leave_request
  for each row execute function hr_guard();

do $$
declare t text;
begin
  foreach t in array array['staff_profile','leave_allowance','leave_request']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on staff_profile, leave_allowance to schoomano_app;
grant select, insert, update (status, decided_by, decision_note, decided_at) on leave_request to schoomano_app;

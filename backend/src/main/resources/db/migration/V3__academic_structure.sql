-- B1 Academic structure (M04): per-programme grades and subjects, per-year terms and sections,
-- campus calendar, and the student's section placement. Grade scales are effective-dated config
-- (key 'assessment.grade_scale' at PROGRAMME scope), so they need no table here.

-- Grades belong to a programme (CBSE Grade 5 and State Grade 5 are different grades).
-- ordinal orders grades for promotion at rollover; pre-primary may use 0 or negatives.
create table grade_level (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  programme_id uuid not null,
  code         text not null check (code ~ '^[A-Z0-9-]{1,12}$'),
  name         text not null check (char_length(name) between 1 and 80),
  ordinal      integer not null check (ordinal between -3 and 20),
  created_at   timestamptz not null default now(),
  unique (tenant_id, programme_id, code),
  unique (tenant_id, programme_id, ordinal),
  unique (tenant_id, id),
  foreign key (tenant_id, programme_id) references programme(tenant_id, id)
);

create table subject (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  programme_id uuid not null,
  code         text not null check (code ~ '^[A-Z0-9-]{2,16}$'),
  name         text not null check (char_length(name) between 1 and 80),
  status       text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  created_at   timestamptz not null default now(),
  unique (tenant_id, programme_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, programme_id) references programme(tenant_id, id)
);

-- Terms are per programme within an academic year (CBSE and State terms may differ).
-- Terms of one programme in one year never overlap and must lie inside the year (trigger below).
create table academic_term (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  academic_year_id uuid not null,
  programme_id     uuid not null,
  label            text not null check (char_length(label) between 1 and 40),
  starts_on        date not null,
  ends_on          date not null,
  created_at       timestamptz not null default now(),
  check (ends_on >= starts_on),
  unique (tenant_id, academic_year_id, programme_id, label),
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, programme_id) references programme(tenant_id, id),
  exclude using gist (academic_year_id with =, programme_id with =, daterange(starts_on, ends_on, '[]') with &&)
);

create table class_section (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  academic_year_id uuid not null,
  grade_level_id   uuid not null,
  code             text not null check (code ~ '^[A-Z0-9-]{1,8}$'),
  capacity         integer check (capacity is null or capacity between 1 and 200),
  created_at       timestamptz not null default now(),
  unique (tenant_id, academic_year_id, grade_level_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, grade_level_id) references grade_level(tenant_id, id)
);

-- Campus calendar for one academic year. programme_id null = applies to every programme on the campus.
create table calendar_event (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  academic_year_id uuid not null,
  programme_id     uuid,
  kind             text not null check (kind in ('HOLIDAY','EXAM','EVENT','WORKING_DAY')),
  title            text not null check (char_length(title) between 1 and 120),
  starts_on        date not null,
  ends_on          date not null,
  created_at       timestamptz not null default now(),
  check (ends_on >= starts_on),
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, programme_id) references programme(tenant_id, id)
);
create index calendar_event_year_idx on calendar_event (tenant_id, academic_year_id, starts_on);

-- A student's section in a year (nullable until sections are assigned; set by import in B2).
alter table student_enrollment add column class_section_id uuid;
alter table student_enrollment add foreign key (tenant_id, class_section_id) references class_section(tenant_id, id);

-- Cross-table consistency the column constraints cannot express:
--   * term / calendar dates lie inside the academic year;
--   * the programme (directly, or through a section's grade) belongs to the year's campus.
create or replace function academic_consistency() returns trigger language plpgsql as $$
declare
  y academic_year%rowtype;
  prog uuid;
  prog_campus uuid;
begin
  select * into y from academic_year where id = new.academic_year_id;
  if not found then
    raise exception 'academic year % not found', new.academic_year_id using errcode = 'foreign_key_violation';
  end if;

  if tg_table_name = 'class_section' then
    select programme_id into prog from grade_level where id = new.grade_level_id;
  elsif tg_table_name = 'student_enrollment' then
    if new.class_section_id is null then
      return new;
    end if;
    select g.programme_id into prog from class_section s join grade_level g on g.id = s.grade_level_id
     where s.id = new.class_section_id and s.academic_year_id = new.academic_year_id;
    if prog is null then
      raise exception 'section belongs to a different academic year' using errcode = 'check_violation';
    end if;
  else
    prog := new.programme_id;
    if new.starts_on < y.starts_on or new.ends_on > y.ends_on then
      raise exception 'dates % to % fall outside academic year % (% to %)',
        new.starts_on, new.ends_on, y.label, y.starts_on, y.ends_on using errcode = 'check_violation';
    end if;
  end if;

  if prog is not null then
    select campus_id into prog_campus from programme where id = prog;
    if prog_campus is distinct from y.campus_id then
      raise exception 'programme is not offered on the academic year''s campus' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end $$;

create trigger academic_term_consistency before insert or update on academic_term
  for each row execute function academic_consistency();
create trigger class_section_consistency before insert or update on class_section
  for each row execute function academic_consistency();
create trigger calendar_event_consistency before insert or update on calendar_event
  for each row execute function academic_consistency();
create trigger student_enrollment_section_consistency before insert or update of class_section_id on student_enrollment
  for each row execute function academic_consistency();

-- Tenant isolation and least privilege, exactly as V2.
do $$
declare t text;
begin
  foreach t in array array['grade_level','subject','academic_term','class_section','calendar_event']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update, delete on grade_level, subject, academic_term, class_section, calendar_event to schoomano_app;

-- B12 admissions (M02): enquiry -> application -> offer -> accepted -> enrolled, with a permanent status history.
--  * Moves follow fixed rules: a stage cannot be skipped, an end state (enrolled, declined, withdrawn, rejected) is final.
--  * Ending an application (reject, decline, withdraw) needs a written reason.
--  * Only one live application per child and guardian mobile, so a family is not entered twice.
--  * Enrolment creates the student, guardian and placement in one step and records who they became.

create table admission_application (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenant(id),
  campus_id           uuid not null,
  academic_year_id    uuid not null,
  grade_level_id      uuid not null,
  child_given_name    text not null check (char_length(btrim(child_given_name)) between 1 and 80),
  child_family_name   text check (child_family_name is null or char_length(child_family_name) <= 80),
  date_of_birth       date,
  guardian_given_name text not null check (char_length(btrim(guardian_given_name)) between 1 and 80),
  guardian_family_name text check (guardian_family_name is null or char_length(guardian_family_name) <= 80),
  guardian_mobile_e164 text not null check (guardian_mobile_e164 ~ '^\+91[6-9][0-9]{9}$'),
  guardian_email      text check (guardian_email is null or char_length(guardian_email) <= 200),
  source              text not null check (source in ('WALK_IN','PHONE','WEBSITE','REFERRAL','OTHER')),
  notes               text check (notes is null or char_length(notes) <= 1000),
  status              text not null default 'ENQUIRY'
                      check (status in ('ENQUIRY','APPLIED','OFFERED','ACCEPTED','ENROLLED','DECLINED','WITHDRAWN','REJECTED')),
  student_person_id   uuid,
  class_section_id    uuid,
  created_by          uuid not null,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  check ((status = 'ENROLLED') = (student_person_id is not null)),
  check ((status = 'ENROLLED') = (class_section_id is not null)),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, grade_level_id) references grade_level(tenant_id, id)
);

create unique index admission_one_live_per_child
  on admission_application (tenant_id, academic_year_id, guardian_mobile_e164, lower(child_given_name), coalesce(date_of_birth, 'epoch'))
  where status in ('ENQUIRY','APPLIED','OFFERED','ACCEPTED');
create index admission_by_year_status on admission_application (tenant_id, academic_year_id, status);

create function admission_guard() returns trigger language plpgsql as $$
declare ok boolean;
begin
  if tg_op = 'UPDATE' then
    if (new.campus_id, new.academic_year_id, new.created_by, new.created_at)
       is distinct from (old.campus_id, old.academic_year_id, old.created_by, old.created_at) then
      raise exception 'campus, year and creator of an application cannot change' using errcode = 'check_violation';
    end if;
    if new.status is distinct from old.status then
      ok := (old.status, new.status) in (
        ('ENQUIRY','APPLIED'), ('ENQUIRY','WITHDRAWN'), ('ENQUIRY','REJECTED'),
        ('APPLIED','OFFERED'), ('APPLIED','WITHDRAWN'), ('APPLIED','REJECTED'),
        ('OFFERED','ACCEPTED'), ('OFFERED','DECLINED'), ('OFFERED','WITHDRAWN'),
        ('ACCEPTED','ENROLLED'), ('ACCEPTED','WITHDRAWN'));
      if not ok then
        raise exception 'an application cannot move from % to %', old.status, new.status using errcode = 'check_violation';
      end if;
    elsif old.status in ('ENROLLED','DECLINED','WITHDRAWN','REJECTED') then
      raise exception 'a % application is closed', old.status using errcode = 'check_violation';
    end if;
    new.updated_at := now();
  end if;
  return new;
end $$;
create trigger admission_guard_check before insert or update on admission_application
  for each row execute function admission_guard();

create table admission_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  application_id uuid not null,
  from_status    text,
  to_status      text not null,
  reason         text check (reason is null or char_length(reason) <= 500),
  actor_person_id uuid not null,
  at             timestamptz not null default now(),
  check (to_status not in ('REJECTED','DECLINED','WITHDRAWN') or char_length(coalesce(btrim(reason), '')) >= 5),
  foreign key (tenant_id, application_id) references admission_application(tenant_id, id)
);
create index admission_event_by_application on admission_event (tenant_id, application_id, at);

create function admission_event_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'admission history is append-only' using errcode = 'check_violation';
end $$;
create trigger admission_event_no_change before update or delete on admission_event
  for each row execute function admission_event_immutable();

do $$
declare t text;
begin
  foreach t in array array['admission_application','admission_event']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, student_person_id, class_section_id, notes, updated_at) on admission_application to schoomano_app;
grant select, insert on admission_event to schoomano_app;

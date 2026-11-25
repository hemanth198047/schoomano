-- B16 / M12 student health. One health record per child (kept current by the nurse) and an append-only log of
-- infirmary visits. The full record is a restricted partition; only the short emergency flag is operational.

create table health_record (
  tenant_id         uuid not null references tenant(id),
  student_person_id uuid not null,
  blood_group       text check (blood_group is null or blood_group in ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
  allergies         text check (allergies is null or char_length(allergies) <= 2000),
  conditions        text check (conditions is null or char_length(conditions) <= 2000),
  medicines         text check (medicines is null or char_length(medicines) <= 2000),
  emergency_flag    text check (emergency_flag is null or char_length(emergency_flag) between 2 and 80),
  updated_by        uuid not null,
  updated_at        timestamptz not null default now(),
  primary key (tenant_id, student_person_id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create table health_visit (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  student_person_id uuid not null,
  visited_at        timestamptz not null default now(),
  complaint         text not null check (char_length(btrim(complaint)) between 2 and 1000),
  action_taken      text check (action_taken is null or char_length(action_taken) <= 1000),
  outcome           text not null check (outcome in ('RETURNED_TO_CLASS','SENT_HOME','REFERRED','OTHER')),
  guardian_informed boolean not null default false,
  recorded_by       uuid not null,
  unique (tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);
create index health_visit_by_student on health_visit (tenant_id, student_person_id, visited_at desc);

create function health_guard() returns trigger language plpgsql as $$
begin
  if tg_table_name = 'health_visit' then
    if tg_op <> 'INSERT' then
      raise exception 'infirmary visits are permanent' using errcode = 'check_violation';
    end if;
    if new.visited_at > now() + interval '5 minutes' then
      raise exception 'a visit cannot be in the future' using errcode = 'check_violation';
    end if;
  elsif tg_op = 'DELETE' then
    raise exception 'health records are not deleted here' using errcode = 'check_violation';
  elsif tg_op = 'INSERT' and new.student_person_id is not null then
    new.updated_at := now();
  else
    new.updated_at := now();
  end if;
  if tg_op = 'INSERT' and not exists (select 1 from student_enrollment where student_person_id = new.student_person_id) then
    raise exception 'health information is kept for enrolled students' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger health_record_check before insert or update or delete on health_record
  for each row execute function health_guard();
create trigger health_visit_check before insert or update or delete on health_visit
  for each row execute function health_guard();

do $$
declare t text;
begin
  foreach t in array array['health_record','health_visit']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on health_record to schoomano_app;
grant select, insert on health_visit to schoomano_app;

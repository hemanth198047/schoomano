-- B62 class teachers (M06). Who is the class teacher of a section.
--  * Only a person who holds the teacher role can be assigned.
--  * Assignments are never edited or deleted: ending one sets ended_at (with who and why), so history stays.
--  * A section may have more than one class teacher (a job share), but the same person only once at a time.
create table class_teacher (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  class_section_id  uuid not null,
  teacher_person_id uuid not null,
  assigned_by       uuid not null,
  assigned_at       timestamptz not null default now(),
  ended_at          timestamptz,
  ended_by          uuid,
  end_reason        text,
  check ((ended_at is null) = (ended_by is null) and (ended_at is null) = (end_reason is null)),
  check (end_reason is null or length(btrim(end_reason)) >= 10),
  unique (tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id),
  foreign key (tenant_id, teacher_person_id) references person(tenant_id, id),
  foreign key (tenant_id, assigned_by) references person(tenant_id, id),
  foreign key (tenant_id, ended_by) references person(tenant_id, id)
);
create unique index class_teacher_live_idx on class_teacher (tenant_id, class_section_id, teacher_person_id) where ended_at is null;
create index class_teacher_teacher_idx on class_teacher (tenant_id, teacher_person_id) where ended_at is null;

create function class_teacher_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'class teacher assignments are kept' using errcode = 'check_violation';
  end if;
  if tg_op = 'INSERT' then
    if new.ended_at is not null then
      raise exception 'a new assignment cannot start ended' using errcode = 'check_violation';
    end if;
    if not exists (select 1 from role_grant r
                    where r.person_id = new.teacher_person_id and r.role_key = 'teacher'
                      and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now())) then
      raise exception 'the person does not hold the teacher role' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  -- update: only ending a live assignment is allowed
  if old.ended_at is not null
     or new.ended_at is null
     or (new.id, new.tenant_id, new.class_section_id, new.teacher_person_id, new.assigned_by, new.assigned_at)
        is distinct from (old.id, old.tenant_id, old.class_section_id, old.teacher_person_id, old.assigned_by, old.assigned_at) then
    raise exception 'an assignment can only be ended, once' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger class_teacher_guard before insert or update or delete on class_teacher
  for each row execute function class_teacher_guard();

alter table class_teacher enable row level security;
alter table class_teacher force row level security;
create policy tenant_isolation on class_teacher using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
grant select, insert, update on class_teacher to schoomano_app;

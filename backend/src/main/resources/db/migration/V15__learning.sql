-- B13 curriculum and learning (M07/M08): a syllabus per grade and subject, coverage per class, and homework.
--  * A unit belongs to a grade and a subject of that grade's programme.
--  * Coverage says "this class has been taught this unit on this date"; it cannot be dated in the future.
--  * Homework is for one class and subject; the only change after setting it is cancelling it.

create table curriculum_unit (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  grade_level_id uuid not null,
  subject_id     uuid not null,
  term_id        uuid,
  ordinal        integer not null check (ordinal between 1 and 200),
  title          text not null check (char_length(btrim(title)) between 2 and 120),
  outcomes       text check (outcomes is null or char_length(outcomes) <= 1000),
  created_by     uuid not null,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, grade_level_id, subject_id, ordinal),
  foreign key (tenant_id, grade_level_id) references grade_level(tenant_id, id),
  foreign key (tenant_id, subject_id) references subject(tenant_id, id)
);

create function curriculum_unit_guard() returns trigger language plpgsql as $$
begin
  if not exists (select 1 from grade_level g join subject s on s.programme_id = g.programme_id
                  where g.id = new.grade_level_id and s.id = new.subject_id) then
    raise exception 'the subject is not taught in this grade''s programme' using errcode = 'check_violation';
  end if;
  if new.term_id is not null and not exists (select 1 from grade_level g join academic_term t on t.programme_id = g.programme_id
                                              where g.id = new.grade_level_id and t.id = new.term_id) then
    raise exception 'the term is not in this grade''s programme' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger curriculum_unit_check before insert on curriculum_unit
  for each row execute function curriculum_unit_guard();

create table unit_coverage (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  class_section_id uuid not null,
  unit_id          uuid not null,
  covered_on       date not null,
  recorded_by      uuid not null,
  created_at       timestamptz not null default now(),
  unique (tenant_id, class_section_id, unit_id),
  foreign key (tenant_id, unit_id) references curriculum_unit(tenant_id, id)
);

create function unit_coverage_guard() returns trigger language plpgsql as $$
begin
  if not exists (select 1 from class_section s join curriculum_unit u on u.grade_level_id = s.grade_level_id
                  where s.id = new.class_section_id and u.id = new.unit_id) then
    raise exception 'the unit is not part of this class''s grade' using errcode = 'check_violation';
  end if;
  if new.covered_on > current_date + 1 then
    raise exception 'coverage cannot be dated in the future' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger unit_coverage_check before insert on unit_coverage
  for each row execute function unit_coverage_guard();

create function learning_no_change() returns trigger language plpgsql as $$
begin
  raise exception '% rows cannot be changed or removed', tg_table_name using errcode = 'check_violation';
end $$;
create trigger unit_coverage_no_change before update or delete on unit_coverage
  for each row execute function learning_no_change();

create table homework (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  class_section_id uuid not null,
  subject_id       uuid not null,
  title            text not null check (char_length(btrim(title)) between 2 and 120),
  instructions     text check (instructions is null or char_length(instructions) <= 2000),
  assigned_on      date not null default current_date,
  due_on           date not null,
  status           text not null default 'ACTIVE' check (status in ('ACTIVE','CANCELLED')),
  created_by       uuid not null,
  created_at       timestamptz not null default now(),
  check (due_on >= assigned_on),
  unique (tenant_id, id),
  foreign key (tenant_id, subject_id) references subject(tenant_id, id)
);
create index homework_by_section on homework (tenant_id, class_section_id, due_on);

create function homework_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    if not exists (select 1 from class_section s join grade_level g on g.id = s.grade_level_id
                     join subject sub on sub.programme_id = g.programme_id
                    where s.id = new.class_section_id and sub.id = new.subject_id) then
      raise exception 'the subject is not taught in this class''s programme' using errcode = 'check_violation';
    end if;
  else
    if (new.class_section_id, new.subject_id, new.title, new.instructions, new.assigned_on, new.due_on, new.created_by)
       is distinct from (old.class_section_id, old.subject_id, old.title, old.instructions, old.assigned_on, old.due_on, old.created_by) then
      raise exception 'homework can only be cancelled' using errcode = 'check_violation';
    end if;
    if old.status = 'CANCELLED' and new.status <> 'CANCELLED' then
      raise exception 'cancelled homework stays cancelled' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end $$;
create trigger homework_check before insert or update on homework
  for each row execute function homework_guard();

do $$
declare t text;
begin
  foreach t in array array['curriculum_unit','unit_coverage','homework']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert on curriculum_unit, unit_coverage to schoomano_app;
grant select, insert, update (status) on homework to schoomano_app;

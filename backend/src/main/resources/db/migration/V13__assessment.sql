-- B11 assessment and report cards (M09).
--  * An assessment is one test or task for one subject of one class in one term, out of N marks, with a weight.
--  * A mark is SCORED (with marks), ABSENT (counts as zero) or EXEMPT (left out of the average).
--  * Once the teacher locks an assessment, any change needs a written reason, and every change is kept in an
--    append-only history.
--  * A published report card is a frozen snapshot: it carries the grade scale that was in force, so a later change
--    to the scale never alters it. Correcting one publishes a new revision with a reason; old revisions stay.

create table assessment (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  class_section_id uuid not null,
  subject_id       uuid not null,
  term_id          uuid not null,
  name             text not null check (char_length(name) between 2 and 80),
  max_marks        numeric(6,2) not null check (max_marks > 0 and max_marks <= 1000),
  weight_percent   integer not null check (weight_percent between 1 and 100),
  held_on          date,
  status           text not null default 'OPEN' check (status in ('OPEN','LOCKED')),
  created_by       uuid not null,
  created_at       timestamptz not null default now(),
  locked_by        uuid,
  locked_at        timestamptz,
  check ((status = 'LOCKED') = (locked_at is not null)),
  unique (tenant_id, class_section_id, subject_id, term_id, name),
  unique (tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id),
  foreign key (tenant_id, subject_id) references subject(tenant_id, id),
  foreign key (tenant_id, term_id) references academic_term(tenant_id, id)
);

-- The subject and the term must belong to the same programme as the class, and the term to the class's year.
create function assessment_consistency() returns trigger language plpgsql as $$
declare cls_programme uuid; cls_year uuid; subj_programme uuid; term_programme uuid; term_year uuid;
begin
  select g.programme_id, s.academic_year_id into cls_programme, cls_year
    from class_section s join grade_level g on g.id = s.grade_level_id where s.id = new.class_section_id;
  select programme_id into subj_programme from subject where id = new.subject_id;
  select programme_id, academic_year_id into term_programme, term_year from academic_term where id = new.term_id;
  if subj_programme is distinct from cls_programme then
    raise exception 'that subject belongs to a different programme from this class' using errcode = 'check_violation';
  end if;
  if term_programme is distinct from cls_programme or term_year is distinct from cls_year then
    raise exception 'that term does not belong to this class''s programme and year' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger assessment_consistency_check before insert on assessment
  for each row execute function assessment_consistency();

-- Only the lock may change. Marks and weights are fixed once marks exist, and a lock cannot be undone.
create function assessment_update_guard() returns trigger language plpgsql as $$
begin
  if (new.class_section_id, new.subject_id, new.term_id, new.name, new.max_marks, new.weight_percent, new.created_by)
     is distinct from (old.class_section_id, old.subject_id, old.term_id, old.name, old.max_marks, old.weight_percent, old.created_by) then
    raise exception 'only the lock of an assessment can change' using errcode = 'check_violation';
  end if;
  if old.status = 'LOCKED' and new.status <> 'LOCKED' then
    raise exception 'a locked assessment cannot be reopened' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger assessment_update_check before update on assessment
  for each row execute function assessment_update_guard();

create table assessment_mark (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  assessment_id     uuid not null,
  student_person_id uuid not null,
  kind              text not null check (kind in ('SCORED','ABSENT','EXEMPT')),
  marks             numeric(6,2) check (marks is null or marks >= 0),
  version           integer not null default 1,
  change_reason     text check (change_reason is null or char_length(change_reason) between 5 and 200),
  updated_by        uuid not null,
  updated_at        timestamptz not null default now(),
  check ((kind = 'SCORED') = (marks is not null)),
  unique (tenant_id, assessment_id, student_person_id),
  unique (tenant_id, id),
  foreign key (tenant_id, assessment_id) references assessment(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create table assessment_mark_history (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  mark_id       uuid not null,
  version       integer not null,
  kind          text not null,
  marks         numeric(6,2),
  change_reason text,
  changed_by    uuid not null,
  changed_at    timestamptz not null default now(),
  foreign key (tenant_id, mark_id) references assessment_mark(tenant_id, id)
);

create function assessment_mark_guard() returns trigger language plpgsql as $$
declare a assessment%rowtype;
begin
  select * into a from assessment where id = new.assessment_id;
  if new.marks is not null and new.marks > a.max_marks then
    raise exception 'marks cannot be more than the maximum of %', a.max_marks using errcode = 'check_violation';
  end if;
  if not exists (select 1 from student_enrollment e
                  where e.student_person_id = new.student_person_id and e.class_section_id = a.class_section_id) then
    raise exception 'that student is not in this class' using errcode = 'check_violation';
  end if;
  if a.status = 'LOCKED' and (new.change_reason is null or (tg_op = 'UPDATE' and new.change_reason is not distinct from old.change_reason)) then
    raise exception 'this assessment is locked: a reason of at least 5 characters is needed to change a mark' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' then
    if (new.kind, new.marks) is not distinct from (old.kind, old.marks) then
      raise exception 'nothing changed' using errcode = 'check_violation';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
  end if;
  return new;
end $$;
create trigger assessment_mark_check before insert or update on assessment_mark
  for each row execute function assessment_mark_guard();

create function assessment_mark_history_write() returns trigger language plpgsql as $$
begin
  insert into assessment_mark_history (tenant_id, mark_id, version, kind, marks, change_reason, changed_by)
  values (new.tenant_id, new.id, new.version, new.kind, new.marks, new.change_reason, new.updated_by);
  return new;
end $$;
create trigger assessment_mark_history_after after insert or update on assessment_mark
  for each row execute function assessment_mark_history_write();

create function assessment_history_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'assessment history is append-only' using errcode = 'check_violation';
end $$;
create trigger assessment_history_no_change before update or delete on assessment_mark_history
  for each row execute function assessment_history_immutable();

create table report_card (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenant(id),
  student_person_id   uuid not null,
  term_id             uuid not null,
  class_section_id    uuid not null,
  revision            integer not null check (revision >= 1),
  grade_scale_version integer not null,
  grade_scale         jsonb not null,
  subjects            jsonb not null,
  overall_percent     numeric(5,1) not null check (overall_percent between 0 and 100),
  overall_grade       text not null,
  revision_reason     text,
  published_by        uuid not null,
  published_at        timestamptz not null default now(),
  check (revision = 1 or char_length(coalesce(revision_reason, '')) >= 5),
  unique (tenant_id, student_person_id, term_id, revision),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, term_id) references academic_term(tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id)
);
create function report_card_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'a published report card cannot be changed; publish a new revision' using errcode = 'check_violation';
end $$;
create trigger report_card_no_change before update or delete on report_card
  for each row execute function report_card_immutable();

do $$
declare t text;
begin
  foreach t in array array['assessment','assessment_mark','assessment_mark_history','report_card']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, locked_by, locked_at) on assessment to schoomano_app;
grant select, insert, update (kind, marks, change_reason, updated_by) on assessment_mark to schoomano_app;
grant select, insert on assessment_mark_history, report_card to schoomano_app;

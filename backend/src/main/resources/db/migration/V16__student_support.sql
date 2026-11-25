-- B14 student support (M10): confidential support cases with append-only notes.
--  * A case is about an enrolled child, in one category, and is open, being monitored, or closed.
--  * Closing needs a reason and is final; a closed case takes no more notes.
--  * Notes are permanent: they cannot be edited or removed.

create table student_support_case (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  student_person_id uuid not null,
  category          text not null check (category in ('LEARNING','BEHAVIOUR','WELLBEING','ATTENDANCE','FAMILY','OTHER')),
  title             text not null check (char_length(btrim(title)) between 2 and 120),
  status            text not null default 'OPEN' check (status in ('OPEN','MONITORING','CLOSED')),
  review_on         date,
  closed_reason     text check (closed_reason is null or char_length(closed_reason) <= 500),
  opened_by         uuid not null,
  opened_on         date not null default current_date,
  closed_at         timestamptz,
  updated_at        timestamptz not null default now(),
  check ((status = 'CLOSED') = (closed_at is not null)),
  check (status <> 'CLOSED' or char_length(coalesce(btrim(closed_reason), '')) >= 5),
  unique (tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);
create index support_case_by_student on student_support_case (tenant_id, student_person_id);
create index support_case_by_status on student_support_case (tenant_id, status, review_on);

create function support_case_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    if not exists (select 1 from student_enrollment where student_person_id = new.student_person_id) then
      raise exception 'a support case needs an enrolled student' using errcode = 'check_violation';
    end if;
  else
    if (new.student_person_id, new.category, new.title, new.opened_by, new.opened_on)
       is distinct from (old.student_person_id, old.category, old.title, old.opened_by, old.opened_on) then
      raise exception 'the child, category and title of a case cannot change' using errcode = 'check_violation';
    end if;
    if old.status = 'CLOSED' then
      raise exception 'a closed case stays closed' using errcode = 'check_violation';
    end if;
    new.updated_at := now();
  end if;
  return new;
end $$;
create trigger support_case_check before insert or update on student_support_case
  for each row execute function support_case_guard();

create table support_case_note (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  case_id     uuid not null,
  body        text not null check (char_length(btrim(body)) between 2 and 4000),
  author_person_id uuid not null,
  created_at  timestamptz not null default now(),
  foreign key (tenant_id, case_id) references student_support_case(tenant_id, id)
);
create index support_note_by_case on support_case_note (tenant_id, case_id, created_at);

create function support_note_guard() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    raise exception 'support notes are permanent' using errcode = 'check_violation';
  end if;
  if (select status from student_support_case where id = new.case_id) = 'CLOSED' then
    raise exception 'a closed case takes no more notes' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger support_note_check before insert or update or delete on support_case_note
  for each row execute function support_note_guard();

do $$
declare t text;
begin
  foreach t in array array['student_support_case','support_case_note']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, review_on, closed_reason, closed_at, updated_at) on student_support_case to schoomano_app;
grant select, insert on support_case_note to schoomano_app;

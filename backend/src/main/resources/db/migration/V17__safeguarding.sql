-- B15 safeguarding (M11): concern reports about a child, a fixed handling path, and a permanent action log.
--  * Path: reported -> triaged -> under review -> escalated or closed (escalated -> closed). Nothing is skipped or reopened.
--  * Escalating or closing needs a written note.
--  * The action log is append-only. Reports record who raised them and cannot be edited.

create table safeguarding_report (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  student_person_id uuid not null,
  category          text not null check (category in ('PHYSICAL','EMOTIONAL','NEGLECT','ONLINE','BULLYING','OTHER')),
  severity          text not null check (severity in ('LOW','MEDIUM','HIGH','IMMEDIATE')),
  description       text not null check (char_length(btrim(description)) between 10 and 4000),
  occurred_on       date,
  reported_by       uuid not null,
  reported_at       timestamptz not null default now(),
  status            text not null default 'REPORTED' check (status in ('REPORTED','TRIAGED','UNDER_REVIEW','ESCALATED','CLOSED')),
  updated_at        timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);
create index safeguarding_by_status on safeguarding_report (tenant_id, status, severity, reported_at);
create index safeguarding_by_student on safeguarding_report (tenant_id, student_person_id);

create function safeguarding_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    if not exists (select 1 from student_enrollment where student_person_id = new.student_person_id) then
      raise exception 'a report needs an enrolled student' using errcode = 'check_violation';
    end if;
    if new.occurred_on is not null and new.occurred_on > current_date then
      raise exception 'the date of the concern cannot be in the future' using errcode = 'check_violation';
    end if;
  else
    if (new.student_person_id, new.category, new.severity, new.description, new.occurred_on, new.reported_by, new.reported_at)
       is distinct from (old.student_person_id, old.category, old.severity, old.description, old.occurred_on, old.reported_by, old.reported_at) then
      raise exception 'a report cannot be edited' using errcode = 'check_violation';
    end if;
    if new.status is distinct from old.status and (old.status, new.status) not in (
        ('REPORTED','TRIAGED'), ('TRIAGED','UNDER_REVIEW'), ('UNDER_REVIEW','ESCALATED'), ('UNDER_REVIEW','CLOSED'),
        ('ESCALATED','CLOSED'), ('TRIAGED','CLOSED')) then
      raise exception 'a report cannot move from % to %', old.status, new.status using errcode = 'check_violation';
    end if;
    if old.status = 'CLOSED' then
      raise exception 'a closed report stays closed' using errcode = 'check_violation';
    end if;
    new.updated_at := now();
  end if;
  return new;
end $$;
create trigger safeguarding_check before insert or update on safeguarding_report
  for each row execute function safeguarding_guard();

create table safeguarding_action (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  report_id   uuid not null,
  from_status text,
  to_status   text,
  note        text check (note is null or char_length(note) <= 4000),
  actor_person_id uuid not null,
  at          timestamptz not null default now(),
  check (note is not null or to_status is not null),
  check (to_status is null or to_status not in ('ESCALATED','CLOSED') or char_length(coalesce(btrim(note), '')) >= 5),
  foreign key (tenant_id, report_id) references safeguarding_report(tenant_id, id)
);
create index safeguarding_action_by_report on safeguarding_action (tenant_id, report_id, at);

create function safeguarding_action_guard() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    raise exception 'safeguarding actions are permanent' using errcode = 'check_violation';
  end if;
  if (select status from safeguarding_report where id = new.report_id) = 'CLOSED' then
    raise exception 'a closed report takes no more actions' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger safeguarding_action_check before insert or update or delete on safeguarding_action
  for each row execute function safeguarding_action_guard();

do $$
declare t text;
begin
  foreach t in array array['safeguarding_report','safeguarding_action']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, updated_at) on safeguarding_report to schoomano_app;
grant select, insert on safeguarding_action to schoomano_app;

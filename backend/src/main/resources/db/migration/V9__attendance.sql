-- B6 attendance (M06). Daily attendance per class section.
--  * Marks carry a version. A device records the version it saw (base_version); if someone else changed the mark
--    since (the office corrected it while the teacher was offline), the device's value is NOT applied: it is kept as
--    an open conflict beside the current value, and the office resolves it. Nothing is silently overwritten.
--  * Every change to a mark is kept in an append-only history.
--  * attendance_submission remembers idempotency keys so a phone that retries after a lost reply cannot double-apply.

create table attendance_session (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  class_section_id uuid not null,
  on_date          date not null,
  created_at       timestamptz not null default now(),
  unique (tenant_id, class_section_id, on_date),
  unique (tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id)
);

create table attendance_mark (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  session_id        uuid not null,
  student_person_id uuid not null,
  status            text not null check (status in ('PRESENT','ABSENT','LATE','LEAVE')),
  version           integer not null default 1 check (version >= 1),
  marked_by         uuid not null,
  marked_at         timestamptz not null,           -- when the teacher tapped (device clock)
  recorded_at       timestamptz not null default now(),
  source            text not null check (source in ('DEVICE','WEB','OFFICE')),
  unique (tenant_id, session_id, student_person_id),
  unique (tenant_id, id),
  foreign key (tenant_id, session_id) references attendance_session(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, marked_by) references person(tenant_id, id)
);

create table attendance_mark_history (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  mark_id     uuid not null,
  version     integer not null,
  status      text not null,
  changed_by  uuid not null,
  changed_at  timestamptz not null default now(),
  source      text not null,
  idempotency_key text,
  foreign key (tenant_id, mark_id) references attendance_mark(tenant_id, id)
);

create table attendance_conflict (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenant(id),
  mark_id             uuid not null,
  attempted_status    text not null check (attempted_status in ('PRESENT','ABSENT','LATE','LEAVE')),
  attempted_by        uuid not null,
  attempted_marked_at timestamptz not null,
  base_version        integer not null,
  current_version     integer not null,
  current_status      text not null,
  state               text not null default 'OPEN' check (state in ('OPEN','RESOLVED')),
  resolution          text check (resolution in ('KEPT_CURRENT','USED_ATTEMPT')),
  resolved_by         uuid,
  resolved_at         timestamptz,
  created_at          timestamptz not null default now(),
  check ((state = 'OPEN') = (resolution is null)),
  foreign key (tenant_id, mark_id) references attendance_mark(tenant_id, id)
);
create index attendance_conflict_open_idx on attendance_conflict (tenant_id, state) where state = 'OPEN';

create table attendance_submission (
  tenant_id       uuid not null references tenant(id),
  idempotency_key text not null check (length(idempotency_key) between 8 and 100),
  session_id      uuid not null,
  request_hash    text not null,
  response        jsonb not null,
  created_by      uuid not null,
  created_at      timestamptz not null default now(),
  primary key (tenant_id, idempotency_key),
  foreign key (tenant_id, session_id) references attendance_session(tenant_id, id)
);

-- History cannot be edited or deleted, even by the application role.
create function attendance_history_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'attendance history is append-only' using errcode = 'check_violation';
end $$;
create trigger attendance_history_immutable before update or delete on attendance_mark_history
  for each row execute function attendance_history_immutable();

-- A mark is only valid for a student enrolled in the session's section, on a school day that has begun.
create function attendance_mark_guard() returns trigger language plpgsql as $$
declare s attendance_session%rowtype; y academic_year%rowtype;
begin
  select * into s from attendance_session where id = new.session_id;
  if tg_op = 'UPDATE' and (new.session_id <> old.session_id or new.student_person_id <> old.student_person_id) then
    raise exception 'a mark cannot move to another student or day' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' and new.version <> old.version + 1 then
    raise exception 'mark version must increase by one' using errcode = 'check_violation';
  end if;
  if tg_op = 'INSERT' and not exists (
       select 1 from student_enrollment e
        where e.student_person_id = new.student_person_id and e.class_section_id = s.class_section_id and e.status = 'ENROLLED') then
    raise exception 'the student is not enrolled in this class' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger attendance_mark_guard before insert or update on attendance_mark
  for each row execute function attendance_mark_guard();

create function attendance_session_guard() returns trigger language plpgsql as $$
declare y academic_year%rowtype;
begin
  select y2.* into y from class_section c join academic_year y2 on y2.id = c.academic_year_id where c.id = new.class_section_id;
  if new.on_date > (now() at time zone 'Asia/Kolkata')::date then
    raise exception 'attendance cannot be taken for a future day' using errcode = 'check_violation';
  end if;
  if new.on_date < y.starts_on or new.on_date > y.ends_on then
    raise exception 'that date is outside the academic year' using errcode = 'check_violation';
  end if;
  if y.status = 'CLOSED' then
    raise exception 'the academic year is closed' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger attendance_session_guard before insert on attendance_session
  for each row execute function attendance_session_guard();

-- Every insert or update of a mark leaves a history row. The idempotency key (if the caller set one) rides along.
create function attendance_mark_history_write() returns trigger language plpgsql as $$
begin
  insert into attendance_mark_history (tenant_id, mark_id, version, status, changed_by, source, idempotency_key)
  values (new.tenant_id, new.id, new.version, new.status, new.marked_by, new.source,
          nullif(current_setting('app.attendance_key', true), ''));
  return new;
end $$;
create trigger attendance_mark_history_write after insert or update on attendance_mark
  for each row execute function attendance_mark_history_write();

-- Applies one mark. Outcomes: CREATED, UPDATED, UNCHANGED, CONFLICT. Runs as the caller (so RLS applies).
-- OFFICE corrections always apply; DEVICE/WEB changes apply only if the mark has not changed since base_version.
create function attendance_apply(p_session uuid, p_student uuid, p_status text, p_base_version integer,
                                 p_by uuid, p_marked_at timestamptz, p_source text)
returns text language plpgsql as $$
declare m attendance_mark%rowtype; t uuid;
begin
  select * into m from attendance_mark where session_id = p_session and student_person_id = p_student for update;
  if not found then
    select tenant_id into t from attendance_session where id = p_session;
    insert into attendance_mark (tenant_id, session_id, student_person_id, status, marked_by, marked_at, source)
    values (t, p_session, p_student, p_status, p_by, p_marked_at, p_source);
    return 'CREATED';
  end if;
  if m.status = p_status then
    return 'UNCHANGED';
  end if;
  if p_source = 'OFFICE' or m.version = p_base_version then
    update attendance_mark set status = p_status, version = m.version + 1, marked_by = p_by, marked_at = p_marked_at,
           recorded_at = now(), source = p_source
     where id = m.id;
    return 'UPDATED';
  end if;
  if not exists (select 1 from attendance_conflict where mark_id = m.id and state = 'OPEN'
                  and attempted_status = p_status and attempted_by = p_by) then
    insert into attendance_conflict (tenant_id, mark_id, attempted_status, attempted_by, attempted_marked_at,
                                     base_version, current_version, current_status)
    values (m.tenant_id, m.id, p_status, p_by, p_marked_at, p_base_version, m.version, m.status);
  end if;
  return 'CONFLICT';
end $$;

-- Resolves a conflict by keeping the current value or using the teacher's attempt (as an office correction).
create function attendance_resolve(p_conflict uuid, p_use_attempt boolean, p_by uuid)
returns void language plpgsql as $$
declare c attendance_conflict%rowtype; m attendance_mark%rowtype;
begin
  select * into c from attendance_conflict where id = p_conflict and state = 'OPEN' for update;
  if not found then
    raise exception 'conflict not found or already resolved' using errcode = 'check_violation';
  end if;
  if p_use_attempt then
    select * into m from attendance_mark where id = c.mark_id for update;
    if m.status <> c.attempted_status then
      update attendance_mark set status = c.attempted_status, version = m.version + 1, marked_by = p_by,
             marked_at = now(), recorded_at = now(), source = 'OFFICE' where id = m.id;
    end if;
  end if;
  update attendance_conflict set state = 'RESOLVED', resolved_by = p_by, resolved_at = now(),
         resolution = case when p_use_attempt then 'USED_ATTEMPT' else 'KEPT_CURRENT' end where id = c.id;
end $$;

do $$
declare t text;
begin
  foreach t in array array['attendance_session','attendance_mark','attendance_mark_history','attendance_conflict','attendance_submission']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update, delete on attendance_session, attendance_mark, attendance_conflict, attendance_submission to schoomano_app;
grant select, insert on attendance_mark_history to schoomano_app;
grant execute on function attendance_apply(uuid, uuid, text, integer, uuid, timestamptz, text), attendance_resolve(uuid, boolean, uuid) to schoomano_app;

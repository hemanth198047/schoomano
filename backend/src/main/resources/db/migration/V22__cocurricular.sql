-- B20 / M18 co-curricular. Clubs, sports and arts activities per campus and year with an optional seat limit, student
-- membership, and session attendance. Membership is made and withdrawn, never edited; attendance is permanent and idempotent.

create table activity (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  campus_id     uuid not null,
  academic_year_id uuid not null,
  name          text not null check (char_length(btrim(name)) between 2 and 80),
  kind          text not null check (kind in ('CLUB','SPORT','ARTS','OTHER')),
  capacity      int check (capacity is null or capacity between 1 and 500),
  lead_name     text check (lead_name is null or char_length(btrim(lead_name)) between 2 and 80),
  status        text not null default 'OPEN' check (status in ('OPEN','CLOSED')),
  unique (tenant_id, id),
  unique (tenant_id, academic_year_id, name),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

create table activity_member (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  activity_id       uuid not null,
  student_person_id uuid not null,
  status            text not null default 'ACTIVE' check (status in ('ACTIVE','WITHDRAWN')),
  joined_on         date not null default current_date,
  left_on           date,
  unique (tenant_id, id),
  foreign key (tenant_id, activity_id) references activity(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  check ((status = 'WITHDRAWN') = (left_on is not null))
);
create unique index activity_one_active_member on activity_member (tenant_id, activity_id, student_person_id) where status = 'ACTIVE';

create table activity_session (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  activity_id  uuid not null,
  session_date date not null,
  topic        text check (topic is null or char_length(btrim(topic)) between 2 and 200),
  unique (tenant_id, id),
  unique (tenant_id, activity_id, session_date),
  foreign key (tenant_id, activity_id) references activity(tenant_id, id)
);

create table activity_attendance (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  session_id        uuid not null,
  student_person_id uuid not null,
  present           boolean not null,
  recorded_by       uuid not null,
  recorded_at       timestamptz not null default now(),
  unique (tenant_id, session_id, student_person_id),
  foreign key (tenant_id, session_id) references activity_session(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);

create function activity_guard() returns trigger language plpgsql as $$
declare cap int; taken int; act_status text; act_year uuid; act_id uuid; sdate date;
begin
  if tg_table_name = 'activity' then
    if tg_op = 'DELETE' then raise exception 'activities are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.campus_id, new.academic_year_id, new.name, new.kind) is distinct from (old.campus_id, old.academic_year_id, old.name, old.kind) then
        raise exception 'an activity cannot be moved or renamed' using errcode = 'check_violation';
      end if;
      if old.status = 'CLOSED' and new.status <> 'CLOSED' then raise exception 'a closed activity stays closed' using errcode = 'check_violation'; end if;
      if new.capacity is not null then
        select count(*) into taken from activity_member m where m.tenant_id = new.tenant_id and m.activity_id = new.id and m.status = 'ACTIVE';
        if taken > new.capacity then raise exception 'the limit is below the current members' using errcode = 'check_violation'; end if;
      end if;
    end if;
    return new;
  end if;
  if tg_table_name = 'activity_member' then
    if tg_op = 'DELETE' then raise exception 'membership is kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.activity_id, new.student_person_id, new.joined_on) is distinct from (old.activity_id, old.student_person_id, old.joined_on) then
        raise exception 'membership cannot be edited, withdraw and join again' using errcode = 'check_violation';
      end if;
      if old.status = 'WITHDRAWN' and new.status <> 'WITHDRAWN' then raise exception 'a withdrawn member stays withdrawn' using errcode = 'check_violation'; end if;
      return new;
    end if;
    select a.capacity, a.status, a.academic_year_id into cap, act_status, act_year from activity a where a.tenant_id = new.tenant_id and a.id = new.activity_id;
    if act_status <> 'OPEN' then raise exception 'the activity is closed' using errcode = 'check_violation'; end if;
    if not exists (select 1 from student_enrollment e where e.student_person_id = new.student_person_id and e.academic_year_id = act_year) then
      raise exception 'the student is not enrolled in that year' using errcode = 'check_violation';
    end if;
    if cap is not null then
      perform pg_advisory_xact_lock(hashtext(new.activity_id::text));
      select count(*) into taken from activity_member m where m.tenant_id = new.tenant_id and m.activity_id = new.activity_id and m.status = 'ACTIVE';
      if taken >= cap then raise exception 'activity full' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  if tg_table_name = 'activity_session' then
    if tg_op <> 'INSERT' then raise exception 'sessions are permanent' using errcode = 'check_violation'; end if;
    select status into act_status from activity where tenant_id = new.tenant_id and id = new.activity_id;
    if act_status <> 'OPEN' then raise exception 'the activity is closed' using errcode = 'check_violation'; end if;
    return new;
  end if;
  -- activity_attendance
  if tg_op <> 'INSERT' then raise exception 'activity attendance is permanent' using errcode = 'check_violation'; end if;
  select s.activity_id, s.session_date into act_id, sdate from activity_session s where s.tenant_id = new.tenant_id and s.id = new.session_id;
  if sdate > (now() at time zone 'Asia/Kolkata')::date then raise exception 'a session cannot be in the future' using errcode = 'check_violation'; end if;
  if not exists (select 1 from activity_member m where m.tenant_id = new.tenant_id and m.activity_id = act_id
                    and m.student_person_id = new.student_person_id and m.joined_on <= sdate and (m.left_on is null or m.left_on > sdate)) then
    raise exception 'the student was not a member on that date' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger activity_check before insert or update or delete on activity for each row execute function activity_guard();
create trigger activity_member_check before insert or update or delete on activity_member for each row execute function activity_guard();
create trigger activity_session_check before insert or update or delete on activity_session for each row execute function activity_guard();
create trigger activity_attendance_check before insert or update or delete on activity_attendance for each row execute function activity_guard();

do $$
declare t text;
begin
  foreach t in array array['activity','activity_member','activity_session','activity_attendance']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (capacity, lead_name, status) on activity to schoomano_app;
grant select, insert, update (status, left_on) on activity_member to schoomano_app;
grant select, insert on activity_session, activity_attendance to schoomano_app;

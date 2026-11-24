-- B7 notices and messaging (M13). Staff announcements and automatic notices (absence, substitution cover) reach people
-- in the app inbox at once and by SMS when allowed.
--  * Recipients are decided in the database: a guardian is reached only while the relationship is ACTIVE or RESTRICTED
--    (a restricted guardian is "notices only"), the notices capability is on, and (for attendance) the attendance
--    capability is on. Suspended or ended relationships receive nothing.
--  * SMS is never sent between 21:00 and 07:00 India time; it waits for 07:00. The in-app inbox is unaffected.
--  * Automatic notices come from the transactional outbox; one outbox event makes at most one notice (source_event).
--  * Cross-school work (the dispatcher reads the outbox of every school) happens in SECURITY DEFINER functions that
--    run as the schema owner, which bypasses RLS in the supplied setup (as auth_resolve_identity does).

create table notice (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  campus_id        uuid not null,
  class_section_id uuid,
  kind             text not null check (kind in ('ANNOUNCEMENT','ATTENDANCE','SUBSTITUTION')),
  title            text not null check (length(title) between 3 and 120),
  body             text not null check (length(body) between 1 and 2000),
  sms_text         text not null check (length(sms_text) between 1 and 160),
  created_by       uuid,
  source_event     uuid,
  created_at       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, class_section_id) references class_section(tenant_id, id),
  foreign key (tenant_id, created_by) references person(tenant_id, id)
);
create unique index notice_source_event_idx on notice (source_event) where source_event is not null;

create table notice_delivery (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenant(id),
  notice_id           uuid not null,
  recipient_person_id uuid not null,
  student_person_id   uuid,
  channel             text not null check (channel in ('IN_APP','SMS')),
  status              text not null default 'PENDING' check (status in ('PENDING','SENT','FAILED','SKIPPED')),
  send_after          timestamptz not null default now(),
  sent_at             timestamptz,
  attempts            integer not null default 0,
  last_error          text,
  skipped_reason      text,
  read_at             timestamptz,
  created_at          timestamptz not null default now(),
  unique (notice_id, recipient_person_id, channel),
  foreign key (tenant_id, notice_id) references notice(tenant_id, id),
  foreign key (tenant_id, recipient_person_id) references person(tenant_id, id),
  check ((status = 'SKIPPED') = (skipped_reason is not null))
);
create index notice_delivery_inbox_idx on notice_delivery (tenant_id, recipient_person_id, created_at desc) where channel = 'IN_APP';
create index notice_delivery_due_idx on notice_delivery (send_after) where channel = 'SMS' and status = 'PENDING';

-- No SMS in the quiet hours (21:00 to 07:00, India time): the message waits for 07:00.
create function notice_next_sms_time(p timestamptz) returns timestamptz language sql stable as $$
  select case
    when (p at time zone 'Asia/Kolkata')::time >= time '21:00'
      then (((p at time zone 'Asia/Kolkata')::date + 1) + time '07:00') at time zone 'Asia/Kolkata'
    when (p at time zone 'Asia/Kolkata')::time < time '07:00'
      then ((p at time zone 'Asia/Kolkata')::date + time '07:00') at time zone 'Asia/Kolkata'
    else p end
$$;

-- Guardians to reach for a student, a section or a whole campus. Internal: not granted to the application role.
create function notice_audience(p_tenant uuid, p_campus uuid, p_section uuid, p_student uuid, p_need_attendance boolean)
  returns table (guardian_id uuid, student_id uuid)
  language sql stable security definer set search_path = public as $$
  select distinct on (r.guardian_person_id) r.guardian_person_id, r.student_person_id
    from guardian_relationship r
    join person g on g.id = r.guardian_person_id and g.status = 'ACTIVE'
    join student_enrollment e on e.student_person_id = r.student_person_id and e.status = 'ENROLLED' and e.tenant_id = r.tenant_id
   where r.tenant_id = p_tenant
     and r.status in ('ACTIVE', 'RESTRICTED')
     and r.can_receive_notices
     and (not p_need_attendance or r.can_view_attendance)
     and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now())
     and (p_student is null or r.student_person_id = p_student)
     and (p_section is null or e.class_section_id = p_section)
     and (p_campus is null or e.campus_id = p_campus)
   order by r.guardian_person_id, r.student_person_id
$$;
revoke all on function notice_audience(uuid, uuid, uuid, uuid, boolean) from public;
revoke all on function notice_audience(uuid, uuid, uuid, uuid, boolean) from schoomano_app;

-- One in-app delivery (sent now) and one SMS delivery (queued, or skipped when there is no number) for a person.
create function notice_deliver(p_tenant uuid, p_notice uuid, p_person uuid, p_student uuid)
  returns void language plpgsql security definer set search_path = public as $$
declare phone text;
begin
  insert into notice_delivery (tenant_id, notice_id, recipient_person_id, student_person_id, channel, status, sent_at)
  values (p_tenant, p_notice, p_person, p_student, 'IN_APP', 'SENT', now())
  on conflict do nothing;
  select mobile_e164 into phone from person where id = p_person;
  if phone is null then
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, student_person_id, channel, status, skipped_reason)
    values (p_tenant, p_notice, p_person, p_student, 'SMS', 'SKIPPED', 'no mobile number')
    on conflict do nothing;
  else
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, student_person_id, channel, send_after)
    values (p_tenant, p_notice, p_person, p_student, 'SMS', notice_next_sms_time(now()))
    on conflict do nothing;
  end if;
end $$;
revoke all on function notice_deliver(uuid, uuid, uuid, uuid) from public;
revoke all on function notice_deliver(uuid, uuid, uuid, uuid) from schoomano_app;

-- Reaches the audience of a staff-written notice (already inserted by the caller under RLS). Returns how many people.
create function notice_publish(p_notice uuid) returns integer
  language plpgsql security definer set search_path = public as $$
declare n notice%rowtype; g record; total integer := 0;
begin
  select * into n from notice where id = p_notice;
  if not found or n.tenant_id is distinct from app_current_tenant() then
    raise exception 'notice not found' using errcode = 'check_violation';
  end if;
  if exists (select 1 from notice_delivery where notice_id = n.id) then
    raise exception 'notice already published' using errcode = 'check_violation';
  end if;
  for g in select * from notice_audience(n.tenant_id, n.campus_id, n.class_section_id, null, false) loop
    perform notice_deliver(n.tenant_id, n.id, g.guardian_id, g.student_id);
    total := total + 1;
  end loop;
  return total;
end $$;
revoke all on function notice_publish(uuid) from public;
grant execute on function notice_publish(uuid) to schoomano_app;

-- Turns outbox events into notices. Safe to run repeatedly and from several instances (skip locked, source_event).
create function notice_process_outbox(p_limit integer) returns integer
  language plpgsql security definer set search_path = public as $$
declare ev outbox_event%rowtype; done integer := 0; nid uuid; g record; stu person%rowtype; campus uuid; sub record;
begin
  for ev in select * from outbox_event
             where published_at is null and attempts < 5
             order by occurred_at limit p_limit for update skip locked
  loop
    begin
      if ev.event_type = 'attendance.absent' then
        select * into stu from person where id = (ev.payload->>'studentId')::uuid and tenant_id = ev.tenant_id;
        select e.campus_id into campus from student_enrollment e
         where e.student_person_id = stu.id and e.status = 'ENROLLED' limit 1;
        if stu.id is not null and campus is not null and exists (
             select 1 from notice_audience(ev.tenant_id, null, null, stu.id, true)) then
          insert into notice (tenant_id, campus_id, class_section_id, kind, title, body, sms_text, source_event)
          values (ev.tenant_id, campus, (ev.payload->>'sectionId')::uuid, 'ATTENDANCE', 'Attendance update',
                  stu.given_name || ' was marked absent on ' || to_char((ev.payload->>'date')::date, 'DD Mon YYYY') || '.',
                  'Schoomano: an attendance update is waiting for you in the app.', ev.id)
          on conflict do nothing returning id into nid;
          if nid is not null then
            for g in select * from notice_audience(ev.tenant_id, null, null, stu.id, true) loop
              perform notice_deliver(ev.tenant_id, nid, g.guardian_id, g.student_id);
            end loop;
          end if;
        end if;
      elsif ev.event_type = 'timetable.substitution_created' then
        select x.substitute_person_id as who, x.on_date, sub2.name as subject, g2.name || ' ' || s.code as section,
               p.label as period, y.campus_id
          into sub
          from substitution x
          join timetable_entry te on te.id = x.timetable_entry_id
          join subject sub2 on sub2.id = te.subject_id
          join class_section s on s.id = te.class_section_id
          join grade_level g2 on g2.id = s.grade_level_id
          join period p on p.id = te.period_id
          join academic_year y on y.id = te.academic_year_id
         where x.id = (ev.payload->>'substitutionId')::uuid and x.tenant_id = ev.tenant_id;
        if sub.who is not null then
          insert into notice (tenant_id, campus_id, kind, title, body, sms_text, source_event)
          values (ev.tenant_id, sub.campus_id, 'SUBSTITUTION', 'You are covering a class',
                  'Please cover ' || sub.subject || ' for ' || sub.section || ' in ' || sub.period || ' on ' ||
                  to_char(sub.on_date, 'DD Mon YYYY') || '.',
                  'Schoomano: you have been asked to cover a class. Open the app for details.', ev.id)
          on conflict do nothing returning id into nid;
          if nid is not null then
            perform notice_deliver(ev.tenant_id, nid, sub.who, null);
          end if;
        end if;
      end if;
      update outbox_event set published_at = now() where id = ev.id;
      done := done + 1;
    exception when others then
      update outbox_event set attempts = attempts + 1, last_error = left(sqlerrm, 300) where id = ev.id;
    end;
    nid := null;
  end loop;
  return done;
end $$;
revoke all on function notice_process_outbox(integer) from public;
grant execute on function notice_process_outbox(integer) to schoomano_app;

-- SMS that are due. A claimed message is leased for two minutes so two instances never send the same one.
create function notice_claim_due_sms(p_limit integer)
  returns table (delivery_id uuid, phone text, text_body text)
  language plpgsql security definer set search_path = public as $$
begin
  return query
  with due as (
    select d.id from notice_delivery d
     where d.channel = 'SMS' and d.status = 'PENDING' and d.send_after <= now()
     order by d.send_after limit p_limit for update skip locked
  ), leased as (
    update notice_delivery d set attempts = d.attempts + 1, send_after = notice_next_sms_time(now() + interval '2 minutes')
      from due where d.id = due.id returning d.id, d.recipient_person_id, d.notice_id
  )
  select l.id, p.mobile_e164, n.sms_text from leased l
    join person p on p.id = l.recipient_person_id
    join notice n on n.id = l.notice_id
   where p.mobile_e164 is not null;
end $$;
revoke all on function notice_claim_due_sms(integer) from public;
grant execute on function notice_claim_due_sms(integer) to schoomano_app;

create function notice_mark_sms(p_delivery uuid, p_ok boolean, p_error text) returns void
  language plpgsql security definer set search_path = public as $$
begin
  if p_ok then
    update notice_delivery set status = 'SENT', sent_at = now(), last_error = null where id = p_delivery and channel = 'SMS';
  else
    update notice_delivery
       set last_error = left(coalesce(p_error, 'send failed'), 300),
           status = case when attempts >= 3 then 'FAILED' else 'PENDING' end,
           send_after = notice_next_sms_time(now() + (attempts * interval '5 minutes'))
     where id = p_delivery and channel = 'SMS';
  end if;
end $$;
revoke all on function notice_mark_sms(uuid, boolean, text) from public;
grant execute on function notice_mark_sms(uuid, boolean, text) to schoomano_app;

do $$
declare t text;
begin
  foreach t in array array['notice','notice_delivery']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert on notice to schoomano_app;
grant select, update (read_at) on notice_delivery to schoomano_app;

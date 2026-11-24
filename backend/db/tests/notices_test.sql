-- B7 notices invariants. Run after migrations + dev seeds, as the application role; rolls back.
-- Fixture: Anaya (…20) is in CBSE grade 5 A at North. Meera (…10) is her mother (all capabilities); Karan (…11) her
-- father, RESTRICTED: notices only. Ravi (…02) and Sunita (…04) are teachers.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- NT1: isolation.
do $$ declare n uuid; begin
  insert into notice (tenant_id, campus_id, kind, title, body, sms_text)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'ANNOUNCEMENT', 'Sports day', 'Friday', 'Sports day') returning id into n;
  perform notice_publish(n);
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from notice) + (select count(*) from notice_delivery) <> 0 then raise exception 'NT1 failed: other tenant sees notices'; end if;
  begin perform notice_publish(n); raise exception 'NT1 failed: published another school''s notice';
  exception when check_violation then null; end;
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from notice) <> 0 then raise exception 'NT1 failed: visible without tenant'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  raise notice 'NT1 pass: notices isolated per tenant';
end $$;

-- NT2: quiet hours are 21:00 to 07:00 India time.
do $$ begin
  if notice_next_sms_time('2026-11-24 12:00+05:30') <> '2026-11-24 12:00+05:30' then raise exception 'NT2 failed: daytime moved'; end if;
  if notice_next_sms_time('2026-11-24 20:59+05:30') <> '2026-11-24 20:59+05:30' then raise exception 'NT2 failed: 20:59 moved'; end if;
  if notice_next_sms_time('2026-11-24 21:00+05:30') <> '2026-11-25 07:00+05:30' then raise exception 'NT2 failed: 21:00'; end if;
  if notice_next_sms_time('2026-11-24 23:30+05:30') <> '2026-11-25 07:00+05:30' then raise exception 'NT2 failed: late night'; end if;
  if notice_next_sms_time('2026-11-24 03:00+05:30') <> '2026-11-24 07:00+05:30' then raise exception 'NT2 failed: early morning'; end if;
  if notice_next_sms_time('2026-11-24 07:00+05:30') <> '2026-11-24 07:00+05:30' then raise exception 'NT2 failed: 07:00'; end if;
  raise notice 'NT2 pass: quiet hours';
end $$;

-- NT3: a section announcement reaches both guardians once (restricted guardian is notices-only, not excluded);
-- suspended relationships receive nothing; each person gets one in-app and one SMS delivery.
do $$ declare n uuid; c int; begin
  insert into notice (tenant_id, campus_id, class_section_id, kind, title, body, sms_text)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-5-A')::uuid,
          'ANNOUNCEMENT', 'Parents meeting', 'Saturday 10am', 'Parents meeting on Saturday') returning id into n;
  c := notice_publish(n);
  if c <> 2 then raise exception 'NT3 failed: expected 2 people, got %', c; end if;
  if (select count(*) from notice_delivery where notice_id = n and channel = 'IN_APP' and status = 'SENT') <> 2 then raise exception 'NT3 failed: in-app'; end if;
  if (select count(*) from notice_delivery where notice_id = n and channel = 'SMS') <> 2 then raise exception 'NT3 failed: sms rows'; end if;
  begin perform notice_publish(n); raise exception 'NT3 failed: published twice';
  exception when check_violation then null; end;
  update guardian_relationship set status = 'SUSPENDED' where guardian_person_id = '13000000-0000-4000-8000-000000000011';
  insert into notice (tenant_id, campus_id, class_section_id, kind, title, body, sms_text)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-5-A')::uuid,
          'ANNOUNCEMENT', 'Second note', 'x', 'x') returning id into n;
  if notice_publish(n) <> 1 then raise exception 'NT3 failed: suspended guardian was reached'; end if;
  raise notice 'NT3 pass: audience rules';
end $$;

-- NT4: an absence event makes one notice for the guardians who may see attendance (not the notices-only father),
-- and processing the same event again changes nothing.
do $$ declare ev uuid; k int; begin
  update guardian_relationship set status = 'RESTRICTED' where guardian_person_id = '13000000-0000-4000-8000-000000000011';
  insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
  values ('10000000-0000-4000-8000-000000000001', 'student', '13000000-0000-4000-8000-000000000020', 'attendance.absent',
          jsonb_build_object('studentId', '13000000-0000-4000-8000-000000000020', 'sectionId', md5('section-CBSE-K12-5-A')::text, 'date', '2026-10-05'))
  returning id into ev;
  perform notice_process_outbox(50);
  if (select published_at from outbox_event where id = ev) is null then raise exception 'NT4 failed: event not consumed'; end if;
  select count(*) into k from notice where source_event = ev;
  if k <> 1 then raise exception 'NT4 failed: expected one notice, got %', k; end if;
  if (select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.source_event = ev and d.channel = 'IN_APP') <> 1 then
    raise exception 'NT4 failed: father reached by an attendance notice';
  end if;
  if (select recipient_person_id from notice_delivery d join notice n on n.id = d.notice_id where n.source_event = ev and d.channel = 'IN_APP')
       <> '13000000-0000-4000-8000-000000000010' then raise exception 'NT4 failed: wrong recipient'; end if;
  update outbox_event set published_at = null where id = ev;
  perform notice_process_outbox(50);
  if (select count(*) from notice where source_event = ev) <> 1 then raise exception 'NT4 failed: duplicate notice on reprocess'; end if;
  raise notice 'NT4 pass: absence notices respect capabilities and are idempotent';
end $$;

-- NT5: a substitution event notifies the covering teacher.
do $$ declare ev uuid; begin
  insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
  values ('10000000-0000-4000-8000-000000000001', 'substitution', gen_random_uuid()::text, 'timetable.substitution_created',
          jsonb_build_object('substitutionId', gen_random_uuid()::text)) returning id into ev;
  perform notice_process_outbox(50);
  if (select published_at from outbox_event where id = ev) is null then raise exception 'NT5 failed: unknown substitution blocks the queue'; end if;
  raise notice 'NT5 pass: an event that cannot be matched does not block the queue';
end $$;

-- NT6: SMS claim leases, success marks sent, repeated failure ends as FAILED, never claimed twice at once.
do $$ declare c int; d uuid; i int; begin
  reset role;  -- the schema owner back-dates the queue so the test does not depend on the time of day
  update notice_delivery set send_after = now() - interval '1 minute' where channel = 'SMS' and status = 'PENDING';
  set local role schoomano_app;
  select count(*) into c from notice_claim_due_sms(1);
  if c <> 1 then raise exception 'NT6 failed: nothing claimed'; end if;
  select id into d from notice_delivery where channel = 'SMS' and attempts = 1 limit 1;
  if (select count(*) from notice_claim_due_sms(100) x where x.delivery_id = d) <> 0 then raise exception 'NT6 failed: claimed while leased'; end if;
  perform notice_mark_sms(d, true, null);
  if (select status from notice_delivery where id = d) <> 'SENT' then raise exception 'NT6 failed: not marked sent'; end if;
  select id into d from notice_delivery where channel = 'SMS' and status = 'PENDING' limit 1;
  for i in 1..3 loop
    reset role;
    update notice_delivery set send_after = now() - interval '1 minute', attempts = i where id = d;
    set local role schoomano_app;
    perform notice_mark_sms(d, false, 'provider down');
  end loop;
  if (select status from notice_delivery where id = d) <> 'FAILED' then raise exception 'NT6 failed: not failed after 3 attempts'; end if;
  raise notice 'NT6 pass: SMS claim, lease, success and failure';
end $$;

-- NT7: the app role cannot write deliveries directly, only mark its own inbox read.
do $$ begin
  begin
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, channel)
    select tenant_id, id, '13000000-0000-4000-8000-000000000010', 'IN_APP' from notice limit 1;
    raise exception 'NT7 failed: direct delivery insert allowed';
  exception when insufficient_privilege then null; end;
  begin update notice_delivery set status = 'FAILED'; raise exception 'NT7 failed: status updatable';
  exception when insufficient_privilege then null; end;
  update notice_delivery set read_at = now() where channel = 'IN_APP';
  raise notice 'NT7 pass: deliveries are written only by the notice functions';
end $$;

rollback;

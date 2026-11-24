-- B5 timetable invariants. Run after migrations + dev seeds, as the application role; rolls back.
-- Fixture: North campus, year 2026-27, CBSE Grade 5 sections A and B; teachers Ravi and Sunita (both 'teacher').

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.sec(c text) returns uuid language sql as $$ select md5('section-CBSE-K12-5-' || c)::uuid $$;
create function pg_temp.per(n int) returns uuid language sql as $$ select md5('period-north-' || n)::uuid $$;
create function pg_temp.room(c text) returns uuid language sql as $$ select md5('room-north-' || c)::uuid $$;
create function pg_temp.subj(c text) returns uuid language sql as $$
  select id from subject where programme_id = '14000000-0000-4000-8000-000000000001' and code = c $$;
-- teachers: Ravi 02, Sunita 04
create function pg_temp.lesson(sec text, dow int, ord int, subj text, teacher text, rm text default null) returns uuid language sql as $$
  insert into timetable_entry (tenant_id, academic_year_id, class_section_id, day_of_week, period_id, subject_id, teacher_person_id, room_id)
  values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', pg_temp.sec(sec), dow, pg_temp.per(ord),
          pg_temp.subj(subj), ('13000000-0000-4000-8000-0000000000' || teacher)::uuid, case when rm is null then null else pg_temp.room(rm) end)
  returning id $$;

-- TT1: isolation.
do $$ begin
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from period) + (select count(*) from room) <> 0 then raise exception 'TT1 failed: visible without tenant'; end if;
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from period) + (select count(*) from room) <> 0 then raise exception 'TT1 failed: other tenant sees periods'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if (select count(*) from period) <> 8 or (select count(*) from room) <> 3 then raise exception 'TT1 failed: seed counts'; end if;
  raise notice 'TT1 pass: timetable tables isolated per tenant';
end $$;

-- TT2: the bell schedule cannot overlap or run against the clock.
do $$ begin
  begin
    insert into period (tenant_id, campus_id, ordinal, label, starts_at, ends_at)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 9, 'Overlap', '13:30', '14:30');
    raise exception 'TT2 failed: overlapping period accepted';
  exception when check_violation then null; end;
  begin
    insert into period (tenant_id, campus_id, ordinal, label, starts_at, ends_at)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 9, 'Backwards', '07:00', '07:45');
    raise exception 'TT2 failed: ordinal before the clock accepted';
  exception when check_violation then null; end;
  insert into period (tenant_id, campus_id, ordinal, label, starts_at, ends_at)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 9, 'Period 7', '14:00', '14:45');
  raise notice 'TT2 pass: overlapping or mis-ordered periods rejected';
end $$;

-- TT3: lessons must agree with the academic structure.
do $$ declare e uuid; begin
  begin perform pg_temp.lesson('A', 1, 3, 'ENG', '02'); raise exception 'TT3 failed: lesson in a break';
  exception when check_violation then null; end;
  begin  -- a State-board subject on a CBSE section
    insert into timetable_entry (tenant_id, academic_year_id, class_section_id, day_of_week, period_id, subject_id, teacher_person_id)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', pg_temp.sec('A'), 1, pg_temp.per(1),
            (select id from subject where programme_id = '14000000-0000-4000-8000-000000000002' and code = 'ENG'), '13000000-0000-4000-8000-000000000002');
    raise exception 'TT3 failed: subject of another programme accepted';
  exception when check_violation then null; end;
  begin perform pg_temp.lesson('A', 1, 1, 'ENG', '10'); raise exception 'TT3 failed: a non-teacher was timetabled';
  exception when check_violation then null; end;
  update room set status = 'RETIRED' where id = pg_temp.room('R102');
  begin perform pg_temp.lesson('A', 1, 1, 'ENG', '02', 'R102'); raise exception 'TT3 failed: retired room accepted';
  exception when check_violation then null; end;
  update room set status = 'ACTIVE' where id = pg_temp.room('R102');
  update subject set status = 'RETIRED' where id = pg_temp.subj('CS');
  begin perform pg_temp.lesson('A', 1, 1, 'CS', '02'); raise exception 'TT3 failed: retired subject accepted';
  exception when check_violation then null; end;
  update subject set status = 'ACTIVE' where id = pg_temp.subj('CS');
  e := pg_temp.lesson('A', 1, 1, 'ENG', '02', 'R101');
  begin perform pg_temp.lesson('A', 1, 1, 'MATH', '04', 'R102'); raise exception 'TT3 failed: second lesson in one section slot';
  exception when unique_violation then null; end;
  raise notice 'TT3 pass: breaks, foreign subjects, non-teachers, retired rooms/subjects and a doubled section slot rejected';
end $$;

-- TT4: a draft may hold clashes, and they are listed.
do $$ declare n int; begin
  perform pg_temp.lesson('B', 1, 1, 'MATH', '02', 'R101');       -- same teacher and room as 5-A at Monday period 1
  perform pg_temp.lesson('B', 2, 1, 'MATH', '04', 'LAB1');
  select count(*) into n from timetable_clashes('15000000-0000-4000-8000-000000000001');
  if n <> 2 then raise exception 'TT4 failed: expected a teacher and a room clash, got %', n; end if;
  if (select count(*) from timetable_clashes('15000000-0000-4000-8000-000000000001') where kind = 'TEACHER' and array_length(entry_ids, 1) = 2) <> 1 then
    raise exception 'TT4 failed: teacher clash not listed'; end if;
  raise notice 'TT4 pass: draft holds clashes and lists them';
end $$;

-- TT5: publishing is refused while clashes exist, and works once they are fixed.
do $$ begin
  begin
    insert into timetable_publication (tenant_id, academic_year_id, status, version, published_at)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', 'PUBLISHED', 1, now());
    raise exception 'TT5 failed: published with clashes';
  exception when check_violation then null; end;
  update timetable_entry set teacher_person_id = '13000000-0000-4000-8000-000000000004', room_id = pg_temp.room('R102')
   where class_section_id = pg_temp.sec('B') and day_of_week = 1;
  insert into timetable_publication (tenant_id, academic_year_id, status, version, published_at)
  values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', 'PUBLISHED', 1, now());
  raise notice 'TT5 pass: publish blocked by clashes, allowed when clean';
end $$;

-- TT6: once published, no change may create a double booking.
do $$ begin
  begin perform pg_temp.lesson('A', 3, 1, 'SCI', '04');  -- fine: Sunita is free on Wednesday
  end;
  begin perform pg_temp.lesson('B', 3, 1, 'ENG', '04'); raise exception 'TT6 failed: teacher double-booked after publish';
  exception when unique_violation then null; end;
  begin perform pg_temp.lesson('B', 4, 1, 'ENG', '02', 'R101'); perform pg_temp.lesson('A', 4, 1, 'ENG', '04', 'R101'); raise exception 'TT6 failed: room double-booked after publish';
  exception when unique_violation then null; end;
  begin
    update timetable_entry set teacher_person_id = '13000000-0000-4000-8000-000000000002'
     where class_section_id = pg_temp.sec('B') and day_of_week = 1;
    raise exception 'TT6 failed: update into a teacher clash accepted';
  exception when unique_violation then null; end;
  raise notice 'TT6 pass: published timetable cannot be double-booked';
end $$;

-- TT7: substitutions (Monday 2026-11-23 is ISO weekday 1).
do $$ declare e uuid; begin
  insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
  values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'teacher', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'test substitute');
  e := (select id from timetable_entry where class_section_id = pg_temp.sec('A') and day_of_week = 1 and period_id = pg_temp.per(1));
  insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
  values ('10000000-0000-4000-8000-000000000001', e, '2026-11-23', '13000000-0000-4000-8000-000000000003', 'Ravi on medical leave');
  -- the same entry and date cannot be covered twice
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-23', '13000000-0000-4000-8000-000000000003', 'again'); raise exception 'TT7 failed: duplicate cover';
  exception when unique_violation then null; end;
  -- wrong weekday
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-24', '13000000-0000-4000-8000-000000000003', 'wrong day'); raise exception 'TT7 failed: wrong weekday accepted';
  exception when check_violation then null; end;
  -- outside the academic year
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2027-04-05', '13000000-0000-4000-8000-000000000003', 'outside'); raise exception 'TT7 failed: outside year accepted';
  exception when check_violation then null; end;
  -- the teacher cannot substitute for themselves; a non-teacher cannot cover
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-30', '13000000-0000-4000-8000-000000000002', 'self'); raise exception 'TT7 failed: self cover';
  exception when check_violation then null; end;
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-30', '13000000-0000-4000-8000-000000000010', 'parent'); raise exception 'TT7 failed: non-teacher cover';
  exception when check_violation then null; end;
  -- a busy substitute: Sunita teaches 5-B Monday period 1, so she cannot cover another Monday period 1 lesson on 2026-11-30
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-30', '13000000-0000-4000-8000-000000000004', 'busy'); raise exception 'TT7 failed: busy substitute accepted';
  exception when unique_violation then null; end;
  -- already covering another class in that period on that date
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        select '10000000-0000-4000-8000-000000000001', id, '2026-11-23', '13000000-0000-4000-8000-000000000003', 'double cover'
          from timetable_entry where class_section_id = pg_temp.sec('B') and day_of_week = 1 and period_id = pg_temp.per(1);
        raise exception 'TT7 failed: substitute covers two classes at once';
  exception when unique_violation then null; end;
  raise notice 'TT7 pass: substitution rules';
end $$;

-- TT8: cover is only possible on a published timetable; deleting a lesson removes its cover.
do $$ declare e uuid; begin
  e := (select id from timetable_entry where class_section_id = pg_temp.sec('A') and day_of_week = 1 and period_id = pg_temp.per(1));
  delete from timetable_entry where id = e;
  if exists (select 1 from substitution where timetable_entry_id = e) then raise exception 'TT8 failed: cover survived its lesson'; end if;
  delete from timetable_publication where academic_year_id = '15000000-0000-4000-8000-000000000001';
  e := (select id from timetable_entry where class_section_id = pg_temp.sec('A') and day_of_week = 3 limit 1);
  begin insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason)
        values ('10000000-0000-4000-8000-000000000001', e, '2026-11-25', '13000000-0000-4000-8000-000000000002', 'draft'); raise exception 'TT8 failed: cover on unpublished timetable';
  exception when check_violation then null; end;
  raise notice 'TT8 pass: cover needs a published timetable and goes with its lesson';
end $$;

rollback;
\echo 'ALL TIMETABLE TESTS PASSED'

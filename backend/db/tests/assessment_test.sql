-- B11 assessment and report cards. Run as the schema owner after migrations + dev seeds; rolls back.
-- Fixture: Anaya (…20) is in CBSE Grade 5 A; Vivaan (…21) is in a State class on the other campus.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.cbse(scode text) returns uuid language sql as
  $$ select s.id from subject s join programme p on p.id = s.programme_id where p.code = 'CBSE-K12' and s.code = scode $$;
create function pg_temp.term(prog text, lbl text) returns uuid language sql as
  $$ select t.id from academic_term t join programme p on p.id = t.programme_id where p.code = prog and t.label = lbl $$;
create function pg_temp.sec() returns uuid language sql as $$ select md5('section-CBSE-K12-5-A')::uuid $$;

-- AS1: subject and term must belong to the class's programme and year.
do $$ declare ok boolean; begin
  begin
    insert into assessment (tenant_id, class_section_id, subject_id, term_id, name, max_marks, weight_percent, created_by)
    select '10000000-0000-4000-8000-000000000001', pg_temp.sec(), s.id, pg_temp.term('CBSE-K12', 'Term 1'), 'Wrong subject', 20, 10, '13000000-0000-4000-8000-000000000001'
      from subject s join programme p on p.id = s.programme_id where p.code = 'STATE-K10' and s.code = 'ENG';
    raise exception 'AS1 failed: a subject from another programme was accepted';
  exception when check_violation then null; end;
  begin
    insert into assessment (tenant_id, class_section_id, subject_id, term_id, name, max_marks, weight_percent, created_by)
    values ('10000000-0000-4000-8000-000000000001', pg_temp.sec(), pg_temp.cbse('ENG'), pg_temp.term('STATE-K10', 'Term 1'), 'Wrong term', 20, 10, '13000000-0000-4000-8000-000000000001');
    raise exception 'AS1 failed: a term from another programme was accepted';
  exception when check_violation then null; end;
  insert into assessment (tenant_id, class_section_id, subject_id, term_id, name, max_marks, weight_percent, created_by)
  values ('10000000-0000-4000-8000-000000000001', pg_temp.sec(), pg_temp.cbse('ENG'), pg_temp.term('CBSE-K12', 'Term 1'), 'Unit test 1', 20, 40, '13000000-0000-4000-8000-000000000001');
  raise notice 'AS1 pass: subject and term must match the class';
end $$;

-- AS2: marks stay within the maximum and only for students of the class.
do $$ declare a uuid := (select id from assessment where name = 'Unit test 1'); begin
  begin
    insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, marks, updated_by)
    values ('10000000-0000-4000-8000-000000000001', a, '13000000-0000-4000-8000-000000000020', 'SCORED', 21, '13000000-0000-4000-8000-000000000001');
    raise exception 'AS2 failed: marks above the maximum accepted';
  exception when check_violation then null; end;
  begin
    insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, marks, updated_by)
    values ('10000000-0000-4000-8000-000000000001', a, '13000000-0000-4000-8000-000000000021', 'SCORED', 10, '13000000-0000-4000-8000-000000000001');
    raise exception 'AS2 failed: a student of another class was accepted';
  exception when check_violation then null; end;
  begin
    insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, updated_by)
    values ('10000000-0000-4000-8000-000000000001', a, '13000000-0000-4000-8000-000000000020', 'SCORED', '13000000-0000-4000-8000-000000000001');
    raise exception 'AS2 failed: a scored mark without marks accepted';
  exception when check_violation then null; end;
  begin
    insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, marks, updated_by)
    values ('10000000-0000-4000-8000-000000000001', a, '13000000-0000-4000-8000-000000000020', 'ABSENT', 5, '13000000-0000-4000-8000-000000000001');
    raise exception 'AS2 failed: an absent mark with marks accepted';
  exception when check_violation then null; end;
  insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, marks, updated_by)
  values ('10000000-0000-4000-8000-000000000001', a, '13000000-0000-4000-8000-000000000020', 'SCORED', 15.5, '13000000-0000-4000-8000-000000000001');
  raise notice 'AS2 pass: marks are bounded and belong to the class';
end $$;

-- AS3: while open a mark changes freely (versioned); once locked, a change needs a reason; history keeps everything.
do $$ declare a uuid := (select id from assessment where name = 'Unit test 1'); m uuid; v int; n int; begin
  select id into m from assessment_mark where assessment_id = a;
  update assessment_mark set marks = 16 where id = m;
  select version into v from assessment_mark where id = m;
  if v <> 2 then raise exception 'AS3 failed: version is % after one change', v; end if;
  begin update assessment_mark set marks = 16 where id = m; raise exception 'AS3 failed: a no-op change accepted';
  exception when check_violation then null; end;

  update assessment set status = 'LOCKED', locked_by = '13000000-0000-4000-8000-000000000001', locked_at = now() where id = a;
  begin update assessment_mark set marks = 18 where id = m; raise exception 'AS3 failed: locked mark changed without a reason';
  exception when check_violation then null; end;
  begin update assessment_mark set marks = 18, change_reason = 'ok' where id = m; raise exception 'AS3 failed: a too-short reason accepted';
  exception when check_violation then null; end;
  update assessment_mark set marks = 18, change_reason = 'Re-added the last question' where id = m;
  begin update assessment_mark set marks = 19 where id = m; raise exception 'AS3 failed: an old reason was reused for a new change';
  exception when check_violation then null; end;
  select count(*) into n from assessment_mark_history where mark_id = m;
  if n <> 3 then raise exception 'AS3 failed: expected 3 history rows, found %', n; end if;
  if (select change_reason from assessment_mark_history where mark_id = m and version = 3) <> 'Re-added the last question' then
    raise exception 'AS3 failed: reason not in history'; end if;
  raise notice 'AS3 pass: locked marks need a reason and every change is in the history';
end $$;

-- AS4: a lock cannot be undone, and the maximum and weight cannot move.
do $$ declare a uuid := (select id from assessment where name = 'Unit test 1'); begin
  begin update assessment set status = 'OPEN', locked_at = null where id = a; raise exception 'AS4 failed: reopened';
  exception when check_violation then null; end;
  begin update assessment set max_marks = 100 where id = a; raise exception 'AS4 failed: maximum changed';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'AS4 pass: lock and maximum are fixed';
end $$;

-- AS5: the history cannot be edited or erased, even by the application role.
do $$ begin
  begin update assessment_mark_history set marks = 1; raise exception 'AS5 failed: history updated';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from assessment_mark_history; raise exception 'AS5 failed: history deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'AS5 pass: history is append-only';
end $$;

-- AS6: report cards are frozen; a correction is a new revision with a reason.
do $$ declare t uuid := pg_temp.term('CBSE-K12', 'Term 1'); begin
  insert into report_card (tenant_id, student_person_id, term_id, class_section_id, revision, grade_scale_version, grade_scale, subjects, overall_percent, overall_grade, published_by)
  values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', t, pg_temp.sec(), 1, 1, '[]', '[]', 88.5, 'B1', '13000000-0000-4000-8000-000000000001');
  begin
    insert into report_card (tenant_id, student_person_id, term_id, class_section_id, revision, grade_scale_version, grade_scale, subjects, overall_percent, overall_grade, published_by)
    values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', t, pg_temp.sec(), 2, 1, '[]', '[]', 90, 'A2', '13000000-0000-4000-8000-000000000001');
    raise exception 'AS6 failed: revision 2 without a reason accepted';
  exception when check_violation then null; end;
  begin
    insert into report_card (tenant_id, student_person_id, term_id, class_section_id, revision, grade_scale_version, grade_scale, subjects, overall_percent, overall_grade, published_by)
    values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', t, pg_temp.sec(), 1, 1, '[]', '[]', 70, 'C', '13000000-0000-4000-8000-000000000001');
    raise exception 'AS6 failed: duplicate revision accepted';
  exception when unique_violation then null; end;
  insert into report_card (tenant_id, student_person_id, term_id, class_section_id, revision, revision_reason, grade_scale_version, grade_scale, subjects, overall_percent, overall_grade, published_by)
  values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', t, pg_temp.sec(), 2, 'Corrected an English mark', 1, '[]', '[]', 90, 'A2', '13000000-0000-4000-8000-000000000001');
  begin update report_card set overall_percent = 100; raise exception 'AS6 failed: report card updated';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from report_card; raise exception 'AS6 failed: report card deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'AS6 pass: report cards are frozen, corrections are new revisions';
end $$;

-- AS7: another school sees none of it.
do $$ begin
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from assessment) + (select count(*) from assessment_mark) + (select count(*) from report_card) + (select count(*) from assessment_mark_history) <> 0 then
    raise exception 'AS7 failed: assessment data visible across schools'; end if;
  raise notice 'AS7 pass: isolation';
end $$;

rollback;

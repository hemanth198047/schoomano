-- B13 curriculum and learning. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.grade() returns uuid language sql as
  $$ select g.id from grade_level g join programme p on p.id = g.programme_id where p.code = 'CBSE-K12' and g.code = 'G5' $$;
create function pg_temp.subj(prog text, c text) returns uuid language sql as
  $$ select s.id from subject s join programme p on p.id = s.programme_id where p.code = prog and s.code = c $$;
create function pg_temp.unit(ord int, prog text default 'CBSE-K12') returns uuid language sql as $$
  insert into curriculum_unit (tenant_id, grade_level_id, subject_id, ordinal, title, created_by)
  values ('10000000-0000-4000-8000-000000000001', pg_temp.grade(), pg_temp.subj(prog, 'ENG'), ord, 'Unit ' || ord, '13000000-0000-4000-8000-000000000001')
  returning id $$;

-- LR1: a unit's subject must be taught in the grade's programme; ordinals are unique per grade and subject.
do $$ begin
  begin perform pg_temp.unit(1, 'STATE-K10'); raise exception 'LR1 failed: subject of another programme accepted';
  exception when check_violation then null; end;
  perform pg_temp.unit(1);
  begin perform pg_temp.unit(1); raise exception 'LR1 failed: duplicate ordinal accepted';
  exception when unique_violation then null; end;
  raise notice 'LR1 pass: units belong to the grade''s programme';
end $$;

-- LR2: coverage is for a class of the unit's grade, not in the future, once only, and permanent.
do $$ declare u uuid; v uuid; sec uuid := md5('section-CBSE-K12-5-A')::uuid; begin
  select id into u from curriculum_unit where title = 'Unit 1' and grade_level_id = pg_temp.grade();
  insert into unit_coverage (tenant_id, class_section_id, unit_id, covered_on, recorded_by)
    values ('10000000-0000-4000-8000-000000000001', sec, u, current_date, '13000000-0000-4000-8000-000000000001');
  begin
    insert into unit_coverage (tenant_id, class_section_id, unit_id, covered_on, recorded_by)
      values ('10000000-0000-4000-8000-000000000001', sec, u, current_date, '13000000-0000-4000-8000-000000000001');
    raise exception 'LR2 failed: covered twice';
  exception when unique_violation then null; end;
  v := pg_temp.unit(2);
  begin
    insert into unit_coverage (tenant_id, class_section_id, unit_id, covered_on, recorded_by)
      values ('10000000-0000-4000-8000-000000000001', sec, v, current_date + 10, '13000000-0000-4000-8000-000000000001');
    raise exception 'LR2 failed: future date accepted';
  exception when check_violation then null; end;
  begin
    insert into unit_coverage (tenant_id, class_section_id, unit_id, covered_on, recorded_by)
      values ('10000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-6-A')::uuid, v, current_date, '13000000-0000-4000-8000-000000000001');
    raise exception 'LR2 failed: another grade accepted';
  exception when check_violation then null; end;
  begin update unit_coverage set covered_on = current_date - 1; raise exception 'LR2 failed: coverage edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from unit_coverage; raise exception 'LR2 failed: coverage deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'LR2 pass: coverage is checked and permanent';
end $$;

-- LR3: homework is for a subject of the class's programme, due on or after it is set, and can only be cancelled.
do $$ declare h uuid; sec uuid := md5('section-CBSE-K12-5-A')::uuid; begin
  begin
    insert into homework (tenant_id, class_section_id, subject_id, title, due_on, created_by)
      values ('10000000-0000-4000-8000-000000000001', sec, pg_temp.subj('STATE-K10', 'ENG'), 'Wrong subject', current_date + 1, '13000000-0000-4000-8000-000000000001');
    raise exception 'LR3 failed: subject of another programme accepted';
  exception when check_violation then null; end;
  begin
    insert into homework (tenant_id, class_section_id, subject_id, title, due_on, created_by)
      values ('10000000-0000-4000-8000-000000000001', sec, pg_temp.subj('CBSE-K12', 'ENG'), 'Late', current_date - 1, '13000000-0000-4000-8000-000000000001');
    raise exception 'LR3 failed: due before set accepted';
  exception when check_violation then null; end;
  insert into homework (tenant_id, class_section_id, subject_id, title, due_on, created_by)
    values ('10000000-0000-4000-8000-000000000001', sec, pg_temp.subj('CBSE-K12', 'ENG'), 'Read chapter 3', current_date + 2, '13000000-0000-4000-8000-000000000001')
    returning id into h;
  begin update homework set title = 'Changed' where id = h; raise exception 'LR3 failed: title changed';
  exception when insufficient_privilege or check_violation then null; end;
  begin update homework set due_on = current_date + 5 where id = h; raise exception 'LR3 failed: due date changed';
  exception when insufficient_privilege or check_violation then null; end;
  update homework set status = 'CANCELLED' where id = h;
  begin update homework set status = 'ACTIVE' where id = h; raise exception 'LR3 failed: cancelled homework revived';
  exception when check_violation then null; end;
  raise notice 'LR3 pass: homework rules';
end $$;

-- LR4: another school sees none of it.
do $$ begin
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from curriculum_unit) + (select count(*) from unit_coverage) + (select count(*) from homework) <> 0 then
    raise exception 'LR4 failed: learning data visible across schools'; end if;
  raise notice 'LR4 pass: isolation';
end $$;
rollback;

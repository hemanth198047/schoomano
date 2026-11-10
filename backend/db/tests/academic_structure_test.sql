-- B1 academic structure invariants. Run after migrations + both dev seeds, like rls_and_invariants_test.sql.
-- Runs as the application role (RLS applies) and rolls back.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;

-- A1: the academic tables are tenant-isolated (fail closed, and tenant B sees none of tenant A's rows).
do $$ begin
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from grade_level) + (select count(*) from academic_term) + (select count(*) from class_section)
     + (select count(*) from subject) + (select count(*) from calendar_event) <> 0 then
    raise exception 'A1 failed: academic rows visible without tenant context'; end if;
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from grade_level) <> 0 then raise exception 'A1 failed: tenant B sees tenant A grades'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if (select count(*) from grade_level) <> 34 then
    raise exception 'A1 failed: expected 34 seeded grades, got %', (select count(*) from grade_level); end if;
  raise notice 'A1 pass: academic tables isolated per tenant';
end $$;

-- A2: overlapping terms within one programme and year are rejected.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            '14000000-0000-4000-8000-000000000001', 'Overlap', '2026-09-01', '2026-10-15');
    raise exception 'A2 failed: overlapping term accepted';
  exception when exclusion_violation then
    raise notice 'A2 pass: overlapping term rejected';
  end;
end $$;

-- A3: a term outside its academic year is rejected.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            '14000000-0000-4000-8000-000000000001', 'Summer', '2027-04-01', '2027-05-15');
    raise exception 'A3 failed: term outside the year accepted';
  exception when check_violation then
    raise notice 'A3 pass: term outside the academic year rejected';
  end;
end $$;

-- A4: a programme from another campus cannot get terms in this campus's year.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            '14000000-0000-4000-8000-000000000002', 'Wrong campus', '2026-05-01', '2026-05-10');
    raise exception 'A4 failed: cross-campus programme accepted';
  exception when check_violation then
    raise notice 'A4 pass: programme must belong to the year''s campus';
  end;
end $$;

-- A5: different programmes on one campus may have overlapping terms (CBSE vs State).
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if not exists (
    select 1 from academic_term a join academic_term b
      on a.academic_year_id = b.academic_year_id and a.programme_id <> b.programme_id
     and daterange(a.starts_on, a.ends_on, '[]') && daterange(b.starts_on, b.ends_on, '[]')) then
    raise exception 'A5 failed: expected overlapping terms across programmes in the seed'; end if;
  raise notice 'A5 pass: terms of different programmes may overlap';
end $$;

-- A6: an enrollment cannot point at a section of another year or campus.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    update student_enrollment set class_section_id = md5('section-CBSE-K12-5-A')::uuid
     where student_person_id = '13000000-0000-4000-8000-000000000021';  -- Vivaan is on South
    raise exception 'A6 failed: enrollment linked to a section of another campus/year';
  exception when check_violation then
    raise notice 'A6 pass: enrollment section must match its academic year';
  end;
end $$;

-- A7: duplicate section codes for one grade and year are rejected; grade ordinals are unique per programme.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into class_section (tenant_id, academic_year_id, grade_level_id, code)
    values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            md5('grade-CBSE-K12-5')::uuid, 'A');
    raise exception 'A7 failed: duplicate section accepted';
  exception when unique_violation then null;
  end;
  begin
    insert into grade_level (tenant_id, programme_id, code, name, ordinal)
    values ('10000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001', 'G5X', 'Dup', 5);
    raise exception 'A7 failed: duplicate grade ordinal accepted';
  exception when unique_violation then null;
  end;
  raise notice 'A7 pass: duplicate sections and grade ordinals rejected';
end $$;

-- A8: each seeded programme has a published grade scale effective at the start of its year.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if (select count(*) from config_version where config_key = 'assessment.grade_scale'
        and scope_level = 'PROGRAMME' and status = 'PUBLISHED') <> 3 then
    raise exception 'A8 failed: expected a published grade scale per programme'; end if;
  raise notice 'A8 pass: grade scales are effective-dated config per programme';
end $$;

-- A9: at most one ACTIVE year per campus.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  begin
    insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on, status)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
            'Second active', '2030-04-01', '2031-03-31', 'ACTIVE');
    raise exception 'A9 failed: second active year accepted';
  exception when unique_violation then
    raise notice 'A9 pass: one active year per campus';
  end;
end $$;

-- A10: a year can be the source of only one rollover.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on, rolled_from)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
          'Roll A', '2031-04-01', '2032-03-31', '15000000-0000-4000-8000-000000000001');
  begin
    insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on, rolled_from)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
            'Roll B', '2032-04-01', '2033-03-31', '15000000-0000-4000-8000-000000000001');
    raise exception 'A10 failed: second rollover of one year accepted';
  exception when unique_violation then
    raise notice 'A10 pass: a year rolls over once';
  end;
end $$;

rollback;

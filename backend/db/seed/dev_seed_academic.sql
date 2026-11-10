-- SYNTHETIC academic structure (B1) for the demo tenant. Runs after dev_seed.sql.
-- Re-runnable: does nothing if grades already exist, or if the base seed is missing.
-- Deterministic ids: md5(<label>)::uuid, so dev logins and tests can refer to rows.

select (not exists (select 1 from tenant where id = '10000000-0000-4000-8000-000000000001')
        or exists (select 1 from grade_level)) as skip_academic \gset
\if :skip_academic
\echo 'Academic seed already present (or base seed missing); nothing to do.'
\quit
\endif

begin;

-- Grades: CBSE 1–12 and State 1–10 on North; State 1–12 on South.
insert into grade_level (id, tenant_id, programme_id, code, name, ordinal)
select md5('grade-' || p.code || '-' || g)::uuid, p.tenant_id, p.id, 'G' || g, 'Grade ' || g, g
  from programme p
  cross join generate_series(1, 12) g
 where p.tenant_id = '10000000-0000-4000-8000-000000000001'
   and g <= case p.code when 'STATE-K10' then 10 else 12 end;

-- Sections for 2026-27: CBSE has A and B (40 each); State programmes have A (45).
insert into class_section (id, tenant_id, academic_year_id, grade_level_id, code, capacity)
select md5('section-' || p.code || '-' || g.ordinal || '-' || s)::uuid, g.tenant_id, y.id, g.id, s,
       case p.board when 'CBSE' then 40 else 45 end
  from grade_level g
  join programme p on p.id = g.programme_id
  join academic_year y on y.campus_id = p.campus_id and y.label = '2026-27'
  cross join unnest(array['A','B']) s
 where s = 'A' or p.board = 'CBSE';

insert into subject (tenant_id, programme_id, code, name)
select p.tenant_id, p.id, s.code, s.name
  from programme p
  join (values ('CBSE','ENG','English'), ('CBSE','HIN','Hindi'), ('CBSE','MATH','Mathematics'),
               ('CBSE','SCI','Science'), ('CBSE','SST','Social Science'), ('CBSE','CS','Computer Science'),
               ('STATE','ENG','English'), ('STATE','REG-LANG','Regional language (state to be confirmed)'),
               ('STATE','MATH','Mathematics'), ('STATE','SCI','Science'), ('STATE','SOC','Social Studies'))
       as s(board, code, name) on s.board = p.board
 where p.tenant_id = '10000000-0000-4000-8000-000000000001';

insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
select p.tenant_id, y.id, p.id, t.label, t.starts_on::date, t.ends_on::date
  from (values ('CBSE-K12',  'Term 1', '2026-04-01', '2026-09-30'),
               ('CBSE-K12',  'Term 2', '2026-10-01', '2027-03-31'),
               ('STATE-K10', 'Term 1', '2026-06-01', '2026-09-30'),
               ('STATE-K10', 'Term 2', '2026-10-01', '2027-01-15'),
               ('STATE-K10', 'Term 3', '2027-01-16', '2027-03-31'),
               ('STATE-K12', 'Term 1', '2026-06-01', '2026-10-15'),
               ('STATE-K12', 'Term 2', '2026-10-16', '2027-03-31')) as t(prog, label, starts_on, ends_on)
  join programme p on p.code = t.prog and p.tenant_id = '10000000-0000-4000-8000-000000000001'
  join academic_year y on y.campus_id = p.campus_id and y.label = '2026-27';

-- Calendar: national holidays on both campuses; one CBSE exam window on North.
insert into calendar_event (tenant_id, academic_year_id, programme_id, kind, title, starts_on, ends_on)
select y.tenant_id, y.id, null, 'HOLIDAY', h.title, h.d::date, h.d::date
  from academic_year y
  cross join (values ('Independence Day', '2026-08-15'), ('Gandhi Jayanti', '2026-10-02'),
                     ('Diwali', '2026-11-08'), ('Republic Day', '2027-01-26')) as h(title, d)
 where y.tenant_id = '10000000-0000-4000-8000-000000000001' and y.label = '2026-27';

insert into calendar_event (tenant_id, academic_year_id, programme_id, kind, title, starts_on, ends_on)
select p.tenant_id, y.id, p.id, 'EXAM', 'Half-yearly examinations', '2026-09-21', '2026-09-30'
  from programme p join academic_year y on y.campus_id = p.campus_id and y.label = '2026-27'
 where p.code = 'CBSE-K12' and p.tenant_id = '10000000-0000-4000-8000-000000000001';

-- Place the synthetic students: Anaya in CBSE Grade 5 A (North), Vivaan in State Grade 3 A (South).
update student_enrollment set class_section_id = md5('section-CBSE-K12-5-A')::uuid
 where student_person_id = '13000000-0000-4000-8000-000000000020';
update student_enrollment set class_section_id = md5('section-STATE-K12-3-A')::uuid
 where student_person_id = '13000000-0000-4000-8000-000000000021';

-- Grade scales as effective-dated config per programme. Bands: lowest minPercent that still earns the grade.
-- CBSE: the 8-point scale. State: a placeholder until the pilot school's state is confirmed.
insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value, effective_from, status,
                            change_note, created_by, published_by, published_at)
select p.tenant_id, 'assessment.grade_scale', 'PROGRAMME', p.id, 1,
       case p.board
         when 'CBSE' then '{"bands":[{"grade":"A1","minPercent":91},{"grade":"A2","minPercent":81},{"grade":"B1","minPercent":71},{"grade":"B2","minPercent":61},{"grade":"C1","minPercent":51},{"grade":"C2","minPercent":41},{"grade":"D","minPercent":33},{"grade":"E","minPercent":0}]}'
         else '{"bands":[{"grade":"A","minPercent":80},{"grade":"B","minPercent":60},{"grade":"C","minPercent":45},{"grade":"D","minPercent":35},{"grade":"E","minPercent":0}]}'
       end::jsonb,
       y.starts_on::timestamptz, 'PUBLISHED',
       case p.board when 'CBSE' then 'CBSE 8-point scale' else 'Placeholder until the state board is confirmed' end,
       '13000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', now()
  from programme p join academic_year y on y.campus_id = p.campus_id and y.label = '2026-27'
 where p.tenant_id = '10000000-0000-4000-8000-000000000001';

commit;

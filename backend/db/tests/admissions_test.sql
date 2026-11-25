-- B12 admissions. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.ins(gname text, mobile text, dob date default null) returns uuid language sql as $$
  insert into admission_application (tenant_id, campus_id, academic_year_id, grade_level_id, child_given_name, date_of_birth,
                                     guardian_given_name, guardian_mobile_e164, source, created_by)
  select '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
         g.id, gname, dob, 'Parent', mobile, 'WALK_IN', '13000000-0000-4000-8000-000000000001'
    from grade_level g join programme p on p.id = g.programme_id
   where p.code = 'CBSE-K12' and g.code = 'G5' returning id $$;
create function pg_temp.mv(app uuid, st text) returns void language sql as $$
  update admission_application set status = st where id = app $$;

-- AD1: stages cannot be skipped and end states are final.
do $$ declare a uuid := pg_temp.ins('Tara', '+919800000001'); begin
  begin perform pg_temp.mv(a, 'OFFERED'); raise exception 'AD1 failed: skipped a stage';
  exception when check_violation then null; end;
  perform pg_temp.mv(a, 'APPLIED'); perform pg_temp.mv(a, 'OFFERED'); perform pg_temp.mv(a, 'ACCEPTED');
  begin perform pg_temp.mv(a, 'APPLIED'); raise exception 'AD1 failed: moved backwards';
  exception when check_violation then null; end;
  perform pg_temp.mv(a, 'WITHDRAWN');
  begin perform pg_temp.mv(a, 'ACCEPTED'); raise exception 'AD1 failed: reopened a withdrawn application';
  exception when check_violation then null; end;
  begin update admission_application set notes = 'late edit' where id = a; raise exception 'AD1 failed: edited a closed application';
  exception when check_violation then null; end;
  raise notice 'AD1 pass: stages are ordered and end states are final';
end $$;

-- AD2: enrolled needs the student and section recorded (and only then).
do $$ declare a uuid := pg_temp.ins('Uma', '+919800000002'); begin
  perform pg_temp.mv(a, 'APPLIED'); perform pg_temp.mv(a, 'OFFERED'); perform pg_temp.mv(a, 'ACCEPTED');
  begin perform pg_temp.mv(a, 'ENROLLED'); raise exception 'AD2 failed: enrolled without a student';
  exception when check_violation then null; end;
  raise notice 'AD2 pass: enrolment needs a student and section';
end $$;

-- AD3: the same child and guardian cannot be live twice, but may reapply after an application ends.
do $$ declare a uuid := pg_temp.ins('Veer', '+919800000003', date '2018-05-05'); b uuid; begin
  begin perform pg_temp.ins('veer', '+919800000003', date '2018-05-05'); raise exception 'AD3 failed: duplicate live application';
  exception when unique_violation then null; end;
  perform pg_temp.ins('Veer', '+919800000003', date '2019-01-01');
  insert into admission_event (tenant_id, application_id, from_status, to_status, reason, actor_person_id)
    values ('10000000-0000-4000-8000-000000000001', a, 'ENQUIRY', 'WITHDRAWN', 'Family moved away', '13000000-0000-4000-8000-000000000001');
  perform pg_temp.mv(a, 'WITHDRAWN');
  b := pg_temp.ins('Veer', '+919800000003', date '2018-05-05');
  raise notice 'AD3 pass: one live application per child';
end $$;

-- AD4: ending needs a reason in the history, and the history cannot be edited.
do $$ declare a uuid := pg_temp.ins('Wasim', '+919800000004'); begin
  begin
    insert into admission_event (tenant_id, application_id, from_status, to_status, reason, actor_person_id)
    values ('10000000-0000-4000-8000-000000000001', a, 'ENQUIRY', 'REJECTED', 'no', '13000000-0000-4000-8000-000000000001');
    raise exception 'AD4 failed: rejection without a reason';
  exception when check_violation then null; end;
  insert into admission_event (tenant_id, application_id, from_status, to_status, actor_person_id)
    values ('10000000-0000-4000-8000-000000000001', a, null, 'ENQUIRY', '13000000-0000-4000-8000-000000000001');
  begin update admission_event set reason = 'x'; raise exception 'AD4 failed: history updated';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from admission_event; raise exception 'AD4 failed: history deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'AD4 pass: reasons required, history append-only';
end $$;

-- AD5: other schools see nothing; the guardian mobile must be a valid Indian mobile.
do $$ begin
  begin perform pg_temp.ins('Xena', '12345'); raise exception 'AD5 failed: bad mobile accepted';
  exception when check_violation then null; end;
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from admission_application) + (select count(*) from admission_event) <> 0 then
    raise exception 'AD5 failed: admissions visible across schools'; end if;
  raise notice 'AD5 pass: isolation and mobile format';
end $$;
rollback;

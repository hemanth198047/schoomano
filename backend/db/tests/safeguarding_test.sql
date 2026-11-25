-- B15 safeguarding. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.raise_concern(student uuid, d text default 'Child reported being hurt at break') returns uuid language sql as $$
  insert into safeguarding_report (tenant_id, student_person_id, category, severity, description, reported_by)
  values ('10000000-0000-4000-8000-000000000001', student, 'PHYSICAL', 'HIGH', d, '13000000-0000-4000-8000-000000000002')
  returning id $$;
create function pg_temp.mv(r uuid, st text) returns void language sql as $$ update safeguarding_report set status = st where id = r $$;

-- SG1: needs an enrolled child and a real description; cannot be edited.
do $$ declare r uuid; begin
  begin perform pg_temp.raise_concern('13000000-0000-4000-8000-000000000002'); raise exception 'SG1 failed: non-student accepted';
  exception when check_violation then null; end;
  begin perform pg_temp.raise_concern('13000000-0000-4000-8000-000000000020', 'short'); raise exception 'SG1 failed: tiny description accepted';
  exception when check_violation then null; end;
  r := pg_temp.raise_concern('13000000-0000-4000-8000-000000000020');
  begin update safeguarding_report set severity = 'LOW' where id = r; raise exception 'SG1 failed: severity edited';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'SG1 pass: reports are complete and fixed';
end $$;

-- SG2: the handling path cannot be skipped or reversed; closing is final.
do $$ declare r uuid := (select id from safeguarding_report limit 1); begin
  begin perform pg_temp.mv(r, 'UNDER_REVIEW'); raise exception 'SG2 failed: skipped triage';
  exception when check_violation then null; end;
  perform pg_temp.mv(r, 'TRIAGED'); perform pg_temp.mv(r, 'UNDER_REVIEW');
  begin perform pg_temp.mv(r, 'REPORTED'); raise exception 'SG2 failed: moved backwards';
  exception when check_violation then null; end;
  perform pg_temp.mv(r, 'ESCALATED'); perform pg_temp.mv(r, 'CLOSED');
  begin perform pg_temp.mv(r, 'TRIAGED'); raise exception 'SG2 failed: reopened';
  exception when check_violation then null; end;
  raise notice 'SG2 pass: fixed path, closing final';
end $$;

-- SG3: escalating or closing needs a note; the action log is permanent and ends with the report.
do $$ declare r uuid := pg_temp.raise_concern('13000000-0000-4000-8000-000000000020', 'Second concern about the same child'); begin
  begin
    insert into safeguarding_action (tenant_id, report_id, from_status, to_status, actor_person_id)
    values ('10000000-0000-4000-8000-000000000001', r, 'UNDER_REVIEW', 'ESCALATED', '13000000-0000-4000-8000-000000000001');
    raise exception 'SG3 failed: escalation without a note';
  exception when check_violation then null; end;
  insert into safeguarding_action (tenant_id, report_id, from_status, to_status, note, actor_person_id)
    values ('10000000-0000-4000-8000-000000000001', r, 'REPORTED', 'TRIAGED', 'Spoke with the class teacher', '13000000-0000-4000-8000-000000000001');
  begin update safeguarding_action set note = 'edited'; raise exception 'SG3 failed: action edited';
  exception when insufficient_privilege or check_violation then null; end;
  begin delete from safeguarding_action; raise exception 'SG3 failed: action deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'SG3 pass: actions are permanent and reasoned';
end $$;

-- SG4: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from safeguarding_report) + (select count(*) from safeguarding_action) <> 0 then
    raise exception 'SG4 failed: safeguarding data visible across schools'; end if;
  raise notice 'SG4 pass: isolation';
end $$;
rollback;

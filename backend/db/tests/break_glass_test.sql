-- B44 break-glass review. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
create temp table bg_ids (n integer, id uuid);
insert into bg_ids select row_number() over (order by id), id from person where tenant_id = '10000000-0000-4000-8000-000000000001' order by id limit 3;
insert into break_glass_grant (id, tenant_id, person_id, resource_type, resource_id, justification, expires_at) values
  ('1b000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', (select id from bg_ids where n = 1), 'health_record', gen_random_uuid(), 'Child collapsed at dispersal, need the emergency flag', now() + interval '3 hours'),
  ('1b000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', (select id from bg_ids where n = 1), 'safeguarding_case', gen_random_uuid(), 'Police asked for the reporting history tonight', now() + interval '3 hours'),
  ('1b000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', (select id from bg_ids where n = 1), 'health_record', gen_random_uuid(), 'Allergic reaction in the canteen, checking medicines', now() + interval '3 hours');
grant select on bg_ids to schoomano_app;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- G1: only health and safeguarding records, and a grant cannot be edited, only reviewed.
do $$ declare other uuid := (select id from bg_ids where n = 2); begin
  begin
    insert into break_glass_grant (tenant_id, person_id, resource_type, resource_id, justification, expires_at)
      values ('10000000-0000-4000-8000-000000000001', other, 'invoice', gen_random_uuid(), 'Needed the invoice urgently for the audit', now() + interval '1 hour');
    raise exception 'G1 failed: any record type accepted';
  exception when check_violation then null; end;
  begin update break_glass_grant set justification = 'Rewritten to say something else entirely' where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G1 failed: justification edited';
  exception when insufficient_privilege then null; end;
  begin update break_glass_grant set expires_at = now() + interval '4 hours' where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G1 failed: expiry extended';
  exception when insufficient_privilege then null; end;
  raise notice 'G1 pass: grants are fixed once made';
end $$;

-- G2: not by the person it was made to; the review needs a note and all three fields; only once.
do $$ declare me uuid := (select id from bg_ids where n = 1); other uuid := (select id from bg_ids where n = 2); begin
  begin update break_glass_grant set reviewed_by = me, reviewed_at = now(), review_outcome = 'APPROPRIATE', review_note = 'Reviewed my own grant'
     where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G2 failed: self review';
  exception when check_violation then null; end;
  begin update break_glass_grant set reviewed_by = other, reviewed_at = now(), review_outcome = 'APPROPRIATE', review_note = 'short'
     where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G2 failed: review without a real note';
  exception when check_violation then null; end;
  begin update break_glass_grant set reviewed_by = other where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G2 failed: half a review';
  exception when check_violation then null; end;
  update break_glass_grant set reviewed_by = other, reviewed_at = now(), review_outcome = 'APPROPRIATE', review_note = 'Matches the incident report'
   where id = '1b000000-0000-4000-8000-000000000001';
  if (select expires_at <= now() from break_glass_grant where id = '1b000000-0000-4000-8000-000000000001') then raise exception 'G2 failed: an appropriate review ended the access'; end if;
  begin update break_glass_grant set review_outcome = 'INAPPROPRIATE', review_note = 'Changed my mind afterwards'
     where id = '1b000000-0000-4000-8000-000000000001';
    raise exception 'G2 failed: reviewed twice';
  exception when check_violation then null; end;
  raise notice 'G2 pass: reviewed once, by someone else, with a note';
end $$;

-- G3: an inappropriate or escalated review ends access that is still running, straight away.
do $$ declare other uuid := (select id from bg_ids where n = 2); begin
  update break_glass_grant set reviewed_by = other, reviewed_at = now(), review_outcome = 'INAPPROPRIATE', review_note = 'No incident was recorded'
   where id = '1b000000-0000-4000-8000-000000000002';
  if (select expires_at > granted_at + interval '1 second' from break_glass_grant where id = '1b000000-0000-4000-8000-000000000002') then raise exception 'G3 failed: inappropriate access kept running'; end if;
  update break_glass_grant set reviewed_by = other, reviewed_at = now(), review_outcome = 'ESCALATED', review_note = 'Passed to the principal today'
   where id = '1b000000-0000-4000-8000-000000000003';
  if (select expires_at > granted_at + interval '1 second' from break_glass_grant where id = '1b000000-0000-4000-8000-000000000003') then raise exception 'G3 failed: escalated access kept running'; end if;
  if (select expires_at <= granted_at from break_glass_grant where id = '1b000000-0000-4000-8000-000000000002') then raise exception 'G3 failed: expiry before the grant'; end if;
  raise notice 'G3 pass: bad reviews end the access';
end $$;

-- G5: staff are told through the SECURITY notice kind, and no other new kind is accepted.
do $$ declare me uuid := (select id from bg_ids where n = 1); begin
  if notice_tell_staff('SECURITY', '12000000-0000-4000-8000-000000000001', 'Emergency access used', 'Someone opened a health record.', array[me], null) <> 1 then
    raise exception 'G5 failed: security notice not delivered'; end if;
  begin perform notice_tell_staff('GOSSIP', '12000000-0000-4000-8000-000000000001', 'x', 'y', array[me], null);
    raise exception 'G5 failed: any kind accepted';
  exception when check_violation then null; end;
  raise notice 'G5 pass: security notices';
end $$;

-- G4: another school sees none of it.
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
do $$ begin
  if (select count(*) from break_glass_grant) <> 0 then raise exception 'G4 failed: another school sees grants'; end if;
  raise notice 'G4 pass: tenants are isolated';
end $$;
rollback;

-- B37 import purge. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into import_batch (id, tenant_id, entity, academic_year_id, file_name, file_sha256, file_table, status, counts, file_problems, missing_refs, created_by, created_at, committed_at, rolled_back_at) values
  ('1d000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'old-committed.csv', '\x01', '[["name"],["Asha"]]', 'COMMITTED', '{"CREATE":1}', '["x"]', '["ADM1"]', '13000000-0000-4000-8000-000000000001', now() - interval '100 days', now() - interval '100 days', null),
  ('1d000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'recent-committed.csv', '\x02', '[["name"],["Ravi"]]', 'COMMITTED', '{}', '[]', '[]', '13000000-0000-4000-8000-000000000001', now() - interval '10 days', now() - interval '10 days', null),
  ('1d000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'stale-open.csv', '\x03', '[["name"],["Farah"]]', 'VALIDATED', '{}', '[]', '[]', '13000000-0000-4000-8000-000000000001', now() - interval '9 days', null, null),
  ('1d000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'fresh-open.csv', '\x04', '[["name"],["Nisha"]]', 'VALIDATED', '{}', '[]', '[]', '13000000-0000-4000-8000-000000000001', now() - interval '1 day', null, null),
  ('1d000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'rolled-back.csv', '\x05', '[["name"],["Meera"]]', 'ROLLED_BACK', '{}', '[]', '[]', '13000000-0000-4000-8000-000000000001', now() - interval '20 days', now() - interval '20 days', now() - interval '12 days');
insert into import_row (tenant_id, batch_id, row_no, external_ref, action, problems, changes, incoming, before_values, link_before, applied) values
  ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', 1, 'ADM1', 'CREATE', '["p"]', '[{"field":"name"}]', '{"name":"Asha"}', '{"name":"Old"}', '{"x":1}', true),
  ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000002', 1, 'ADM2', 'CREATE', '[]', '[{"field":"name"}]', '{"name":"Ravi"}', null, null, true);

-- P1: settled imports past retention are blanked; recent and fresh ones are untouched; a stale checked file is discarded first.
do $$ declare n integer; begin
  select coalesce(sum(purged), 0) into n from import_purge(90, 7);
  if n <> 3 then raise exception 'P1 failed: purged % batches', n; end if;
  if (select count(*) from import_batch where purged_at is not null and id in
        ('1d000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000003', '1d000000-0000-4000-8000-000000000005')) <> 3 then
    raise exception 'P1 failed: wrong batches purged'; end if;
  if (select status from import_batch where id = '1d000000-0000-4000-8000-000000000003') <> 'DISCARDED' then raise exception 'P1 failed: stale file not discarded'; end if;
  if exists (select 1 from import_batch where purged_at is not null and (file_table <> '[]'::jsonb or file_problems <> '[]'::jsonb or missing_refs <> '[]'::jsonb)) then
    raise exception 'P1 failed: file data kept'; end if;
  if (select count(*) from import_batch where purged_at is null and id in ('1d000000-0000-4000-8000-000000000002', '1d000000-0000-4000-8000-000000000004')) <> 2 then
    raise exception 'P1 failed: a live batch was purged'; end if;
  if exists (select 1 from import_row where batch_id = '1d000000-0000-4000-8000-000000000001' and (incoming <> '{}'::jsonb or changes <> '[]'::jsonb or problems <> '[]'::jsonb or before_values is not null or link_before is not null)) then
    raise exception 'P1 failed: row values kept'; end if;
  if (select incoming from import_row where batch_id = '1d000000-0000-4000-8000-000000000002') <> '{"name":"Ravi"}'::jsonb then raise exception 'P1 failed: a live row was blanked'; end if;
  if (select external_ref || action from import_row where batch_id = '1d000000-0000-4000-8000-000000000001') <> 'ADM1CREATE' then raise exception 'P1 failed: record of the import lost'; end if;
  if (select counts from import_batch where id = '1d000000-0000-4000-8000-000000000001') <> '{"CREATE":1}'::jsonb then raise exception 'P1 failed: counts lost'; end if;
  raise notice 'P1 pass: settled imports are blanked, the record stays';
end $$;

-- P2: purging is permanent and run twice does nothing more.
do $$ begin
  if (select coalesce(sum(purged), 0) from import_purge(90, 7)) <> 0 then raise exception 'P2 failed: purged twice'; end if;
  begin update import_batch set status = 'COMMITTED' where id = '1d000000-0000-4000-8000-000000000003'; raise exception 'P2 failed: purged status changed';
  exception when check_violation then null; end;
  begin update import_batch set file_table = '[["x"]]' where id = '1d000000-0000-4000-8000-000000000001'; raise exception 'P2 failed: file restored';
  exception when check_violation then null; end;
  begin update import_batch set purged_at = now() where id = '1d000000-0000-4000-8000-000000000002'; raise exception 'P2 failed: purge without emptying the file';
  exception when check_violation then null; end;
  begin update import_batch set purged_at = now(), file_table = '[]' where id = '1d000000-0000-4000-8000-000000000004'; raise exception 'P2 failed: open file purged';
  exception when check_violation then null; end;
  begin perform import_purge(0, 7); raise exception 'P2 failed: zero retention accepted';
  exception when check_violation then null; end;
  raise notice 'P2 pass: permanent and repeatable';
end $$;
rollback;

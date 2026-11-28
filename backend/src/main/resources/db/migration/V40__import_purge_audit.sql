-- B41 / M29: the housekeeping that discards a stale checked file and blanks a settled import now leaves a record in the
-- school's audit log (actor kind SYSTEM), one event per import, so it can be shown later that the data was removed and when.
-- Nothing about the students is written, only the import's id, its state and how many rows it had.

create or replace function import_purge(p_committed_days integer, p_open_days integer)
  returns table (t_id uuid, purged integer)
  language plpgsql security definer set search_path = public as $$
declare b record; rows_n integer;
begin
  if p_committed_days < 1 or p_open_days < 1 then raise exception 'retention must be at least one day' using errcode = 'check_violation'; end if;

  for b in select id, tenant_id from import_batch
            where status = 'VALIDATED' and purged_at is null and created_at < clock_timestamp() - make_interval(days => p_open_days)
            order by created_at limit 200 for update skip locked
  loop
    update import_batch set status = 'DISCARDED' where id = b.id;
    insert into audit_event (tenant_id, actor_kind, action, resource_type, resource_id, outcome, reason, details)
    values (b.tenant_id, 'SYSTEM', 'import.discarded', 'import_batch', b.id::text, 'SUCCESS',
            'Checked but not applied within ' || p_open_days || ' days', '{}'::jsonb);
  end loop;

  for b in select i.id, i.tenant_id, i.status from import_batch i
            where i.purged_at is null
              and ((i.status = 'COMMITTED' and i.committed_at < clock_timestamp() - make_interval(days => p_committed_days))
                or (i.status in ('ROLLED_BACK', 'DISCARDED') and coalesce(i.rolled_back_at, i.created_at) < clock_timestamp() - make_interval(days => p_open_days)))
            order by i.created_at limit 200 for update skip locked
  loop
    update import_row r set incoming = '{}'::jsonb, changes = '[]'::jsonb, problems = '[]'::jsonb, before_values = null, link_before = null
     where r.tenant_id = b.tenant_id and r.batch_id = b.id;
    get diagnostics rows_n = row_count;
    update import_batch set file_table = '[]'::jsonb, file_problems = '[]'::jsonb, missing_refs = '[]'::jsonb, purged_at = clock_timestamp()
     where id = b.id;
    insert into audit_event (tenant_id, actor_kind, action, resource_type, resource_id, outcome, reason, details)
    values (b.tenant_id, 'SYSTEM', 'import.purged', 'import_batch', b.id::text, 'SUCCESS',
            'File and row values removed after the retention period',
            jsonb_build_object('status', b.status, 'rows', rows_n));
    t_id := b.tenant_id; purged := 1;
    return next;
  end loop;
end $$;

-- B37 / M29: purging import files. An import keeps the whole parsed file and, per row, the incoming and previous values of
-- every student, which is personal data the school no longer needs once the import is settled. After a retention period
-- those values are blanked for good: committed imports after the rollback window, discarded and rolled-back ones soon after,
-- and a file that was checked but never committed is discarded and then purged. What stays is the record that an import
-- happened (file name, counts, who and when, each row's reference and action). A purged import can no longer be rolled back.

alter table import_batch add column purged_at timestamptz;

create function import_batch_purge_guard() returns trigger language plpgsql as $$
begin
  if old.purged_at is not null and (new.status, new.file_table, new.purged_at) is distinct from (old.status, old.file_table, old.purged_at) then
    raise exception 'a purged import stays purged' using errcode = 'check_violation';
  end if;
  if new.purged_at is not null and old.purged_at is null
     and (new.file_table <> '[]'::jsonb or new.status not in ('COMMITTED', 'ROLLED_BACK', 'DISCARDED')) then
    raise exception 'only a settled import can be purged, and its file must be emptied' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger import_batch_purge_check before update on import_batch for each row execute function import_batch_purge_guard();

-- Cross-school housekeeping: discards stale checked files, then blanks every settled import past its retention.
-- Returns, for each school, how many imports were purged.
create function import_purge(p_committed_days integer, p_open_days integer)
  returns table (t_id uuid, purged integer)
  language plpgsql security definer set search_path = public as $$
begin
  if p_committed_days < 1 or p_open_days < 1 then raise exception 'retention must be at least one day' using errcode = 'check_violation'; end if;
  update import_batch set status = 'DISCARDED'
   where status = 'VALIDATED' and purged_at is null and created_at < clock_timestamp() - make_interval(days => p_open_days);
  return query
  with due as (
    select b.id, b.tenant_id from import_batch b
     where b.purged_at is null
       and ((b.status = 'COMMITTED' and b.committed_at < clock_timestamp() - make_interval(days => p_committed_days))
         or (b.status in ('ROLLED_BACK', 'DISCARDED') and coalesce(b.rolled_back_at, b.created_at) < clock_timestamp() - make_interval(days => p_open_days)))
     order by b.created_at limit 200 for update skip locked
  ), blanked as (
    update import_row r set incoming = '{}'::jsonb, changes = '[]'::jsonb, problems = '[]'::jsonb, before_values = null, link_before = null
      from due where r.tenant_id = due.tenant_id and r.batch_id = due.id returning r.batch_id
  ), done as (
    update import_batch b set file_table = '[]'::jsonb, file_problems = '[]'::jsonb, missing_refs = '[]'::jsonb, purged_at = clock_timestamp()
      from due where b.id = due.id returning b.tenant_id
  )
  select d.tenant_id, count(*)::integer from done d group by d.tenant_id;
end $$;
revoke all on function import_purge(integer, integer) from public;
grant execute on function import_purge(integer, integer) to schoomano_app;

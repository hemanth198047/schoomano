-- B10 support console (M30).
--  * Platform support enters a school only through a grant the school approved (support_access_grant, V1): named
--    person, written purpose, at most 8 hours, revocable at any time. This migration adds who revoked it, a way to
--    find a live grant from a sign-in, and a check that the audit trail has not been altered.
--  * The audit chain hashed the timestamp as text in the writer's session time zone, so a check from another zone
--    could not reproduce it. New rows are hashed in UTC; older rows are checked for linkage only.

alter table support_access_grant add column revoked_by uuid;
alter table support_access_grant add column created_at timestamptz not null default now();
alter table support_access_grant add foreign key (tenant_id, revoked_by) references person(tenant_id, id);
create index support_grant_live_idx on support_access_grant (support_user_ref, tenant_id) where revoked_at is null;

-- A sign-in from outside any school asks: do I hold a live grant for this school? Returns it, or nothing.
create function support_resolve(p_ref text, p_slug text)
  returns table (grant_id uuid, tenant_id uuid, tenant_slug text, tenant_name text, starts_at timestamptz, expires_at timestamptz)
  language sql security definer set search_path = public stable as $$
  select g.id, t.id, t.slug, t.display_name, g.starts_at, g.expires_at
    from support_access_grant g join tenant t on t.id = g.tenant_id
   where g.support_user_ref = p_ref and t.slug = p_slug
     and g.revoked_at is null and g.starts_at <= now() and g.expires_at > now()
   order by g.expires_at desc limit 1
$$;
revoke all on function support_resolve(text, text) from public;
revoke all on function support_resolve(text, text) from schoomano_app;
grant execute on function support_resolve(text, text) to schoomano_app;

alter table audit_event add column hash_in_utc boolean not null default false;

create or replace function audit_event_chain() returns trigger language plpgsql set timezone = 'UTC' as $$
declare
  last_hash bytea;
  last_seq  bigint;
begin
  perform pg_advisory_xact_lock(hashtextextended(new.tenant_id::text, 7));
  select seq, hash into last_seq, last_hash
    from audit_event where tenant_id = new.tenant_id order by seq desc limit 1;
  new.seq := coalesce(last_seq, 0) + 1;
  new.prev_hash := last_hash;
  new.hash_in_utc := true;
  new.hash := sha256(convert_to(concat_ws('|',
      encode(coalesce(last_hash, '\x'::bytea), 'hex'), new.tenant_id, new.seq, new.occurred_at,
      new.actor_person_id, new.actor_kind, new.action, new.resource_type, new.resource_id,
      new.outcome, new.reason, new.correlation_id, new.policy_version, new.details::text), 'UTF8'));
  return new;
end $$;

-- Walks one school's audit trail in order. Returns the checked count and, if anything is wrong, the first bad
-- sequence number and why: a gap, a broken link to the previous row, or (for rows hashed in UTC) an altered row.
create function audit_verify(p_tenant uuid)
  returns table (checked bigint, broken_seq bigint, problem text)
  language plpgsql stable set timezone = 'UTC' as $$
declare r audit_event%rowtype; prev_seq bigint := 0; prev_hash bytea := null; n bigint := 0; expect bytea;
begin
  for r in select * from audit_event where audit_event.tenant_id = p_tenant order by seq loop
    n := n + 1;
    if r.seq <> prev_seq + 1 then
      return query select n, r.seq, 'a row is missing before this one'::text; return;
    end if;
    if r.prev_hash is distinct from prev_hash then
      return query select n, r.seq, 'the link to the previous row is broken'::text; return;
    end if;
    if r.hash_in_utc then
      expect := sha256(convert_to(concat_ws('|',
        encode(coalesce(prev_hash, '\x'::bytea), 'hex'), r.tenant_id, r.seq, r.occurred_at,
        r.actor_person_id, r.actor_kind, r.action, r.resource_type, r.resource_id,
        r.outcome, r.reason, r.correlation_id, r.policy_version, r.details::text), 'UTF8'));
      if expect <> r.hash then
        return query select n, r.seq, 'this row was changed after it was written'::text; return;
      end if;
    end if;
    prev_seq := r.seq;
    prev_hash := r.hash;
  end loop;
  return query select n, null::bigint, null::text;
end $$;
grant execute on function audit_verify(uuid) to schoomano_app;

-- B54 / M30: the school is told when platform support first uses an access it approved. The first use of a grant is
-- recorded once, atomically, so several instances (or many requests) send one notice.
alter table support_access_grant add column first_used_at timestamptz;

create function support_first_use(p_grant uuid) returns table (support_user_ref text, expires_at timestamptz)
  language sql security definer set search_path = public as $$
  update support_access_grant g set first_used_at = now()
   where g.id = p_grant and g.first_used_at is null and g.revoked_at is null and g.expires_at > now()
  returning g.support_user_ref, g.expires_at
$$;
revoke all on function support_first_use(uuid) from public;
grant execute on function support_first_use(uuid) to schoomano_app;

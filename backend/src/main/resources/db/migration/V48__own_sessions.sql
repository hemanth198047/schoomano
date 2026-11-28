-- B60 / M01: a parent can see where their number is signed in and end the other devices (a lost phone) without calling the
-- office. The caller is identified only by the access token they hold; they can reach only sessions of their own number.
create function auth_my_sessions(p_access_hash bytea, p_now timestamptz)
  returns table (session_id uuid, device_label text, created_at timestamptz, last_seen_at timestamptz, is_current boolean)
  language sql stable security definer set search_path = public as $$
  select s.id, s.device_label, s.created_at, s.last_seen_at, (s.access_hash = p_access_hash)
    from auth_session s
   where s.phone_e164 = (select c.phone_e164 from auth_session c
                          where c.access_hash = p_access_hash and c.revoked_at is null and c.access_expires_at > p_now)
     and s.revoked_at is null and s.refresh_expires_at > p_now
   order by (s.access_hash = p_access_hash) desc, s.last_seen_at desc
$$;
revoke all on function auth_my_sessions(bytea, timestamptz) from public;
grant execute on function auth_my_sessions(bytea, timestamptz) to schoomano_app;

-- Ends one other session of the caller's number (never the one in use: that is signing out). True when it ended.
create function auth_my_session_revoke(p_access_hash bytea, p_session uuid, p_now timestamptz) returns boolean
  language plpgsql volatile security definer set search_path = public as $$
declare phone text;
begin
  select c.phone_e164 into phone from auth_session c where c.access_hash = p_access_hash and c.revoked_at is null and c.access_expires_at > p_now;
  if phone is null then return false; end if;
  update auth_session set revoked_at = p_now, revoke_reason = 'LOGOUT'
   where id = p_session and phone_e164 = phone and revoked_at is null and access_hash <> p_access_hash;
  return found;
end $$;
revoke all on function auth_my_session_revoke(bytea, uuid, timestamptz) from public;
grant execute on function auth_my_session_revoke(bytea, uuid, timestamptz) to schoomano_app;

-- Ends every other session of the caller's number.
create function auth_my_sessions_revoke_others(p_access_hash bytea, p_now timestamptz) returns int
  language plpgsql volatile security definer set search_path = public as $$
declare phone text; n int;
begin
  select c.phone_e164 into phone from auth_session c where c.access_hash = p_access_hash and c.revoked_at is null and c.access_expires_at > p_now;
  if phone is null then return 0; end if;
  update auth_session set revoked_at = p_now, revoke_reason = 'LOGOUT'
   where phone_e164 = phone and revoked_at is null and access_hash <> p_access_hash;
  get diagnostics n = row_count;
  return n;
end $$;
revoke all on function auth_my_sessions_revoke_others(bytea, timestamptz) from public;
grant execute on function auth_my_sessions_revoke_others(bytea, timestamptz) to schoomano_app;

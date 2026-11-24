-- B4 sign-in (M01), ADR 0009: parents sign in with a phone OTP; the session is an opaque, hashed bearer token.
--
-- These tables are used BEFORE a tenant is known (the phone number is all we have), so they carry no tenant_id
-- and no RLS policy. Instead the application role has NO privileges on them at all: every read and write goes
-- through the narrow SECURITY DEFINER functions below, exactly like auth_resolve_identity (V2). As there, the
-- function owner must be able to read `identity` across tenants (it bypasses RLS in the supplied setup).
--
-- Nothing secret is stored in clear: codes are HMAC hashes (the pepper lives only in the application's
-- environment), tokens are SHA-256 hashes of 256-bit random values, and IP addresses are keyed hashes.
-- The application passes "now" and every limit in as parameters so the rules live in one place (the domain
-- class OtpPolicy) and can be tested with a fixed clock.

create table auth_otp_challenge (
  id           uuid primary key default gen_random_uuid(),
  phone_e164   text not null check (phone_e164 ~ '^\+91[6-9][0-9]{9}$'),
  ip_hash      bytea not null,
  code_salt    bytea not null,
  code_hash    bytea not null,
  -- false: the number is not on any guardian record. No SMS is sent and the code can never verify, but the
  -- challenge is stored and counted like any other so the response, the limits and the timing do not reveal
  -- whether a number is known (ADR 0009).
  deliverable  boolean not null,
  created_at   timestamptz not null,
  expires_at   timestamptz not null,
  attempts     smallint not null default 0 check (attempts >= 0),
  consumed_at  timestamptz,
  check (expires_at > created_at)
);
create index auth_otp_phone_idx on auth_otp_challenge (phone_e164, created_at desc);
create index auth_otp_ip_idx on auth_otp_challenge (ip_hash, created_at desc);

create table auth_session (
  id                 uuid primary key default gen_random_uuid(),
  phone_e164         text not null check (phone_e164 ~ '^\+91[6-9][0-9]{9}$'),
  access_hash        bytea not null unique,
  refresh_hash       bytea not null unique,
  prev_refresh_hash  bytea,                       -- the refresh token just replaced; presenting it again means theft
  device_label       text check (device_label is null or char_length(device_label) <= 80),
  created_at         timestamptz not null,
  access_expires_at  timestamptz not null,
  refresh_expires_at timestamptz not null,
  last_seen_at       timestamptz not null,
  revoked_at         timestamptz,
  revoke_reason      text check (revoke_reason is null or revoke_reason in
                       ('LOGOUT','REFRESH_REUSE','NUMBER_CHANGED','ADMIN')),
  check (refresh_expires_at >= access_expires_at),
  check ((revoked_at is null) = (revoke_reason is null))
);
create index auth_session_phone_idx on auth_session (phone_e164) where revoked_at is null;
create index auth_session_prev_refresh_idx on auth_session (prev_refresh_hash) where prev_refresh_hash is not null;

revoke all on auth_otp_challenge, auth_session from public;
revoke all on auth_otp_challenge, auth_session from schoomano_app;

-- ---------------------------------------------------------------------------------------------------------
-- Request a code. One atomic step per phone number (advisory lock), so two concurrent requests cannot both
-- slip under the resend gap or the hourly cap. Outcomes: SENT, TOO_SOON, LIMITED.
create or replace function auth_otp_issue(
  p_phone text, p_ip_hash bytea, p_salt bytea, p_hash bytea, p_now timestamptz,
  p_ttl_seconds int, p_resend_seconds int,
  p_phone_hour_max int, p_phone_day_max int, p_ip_hour_max int)
  returns table (outcome text, retry_after_seconds int, deliverable boolean)
  language plpgsql volatile security definer set search_path = public as $$
declare
  last_at timestamptz;
  oldest_hour timestamptz;
  n_phone_hour int;
  n_phone_day int;
  n_ip_hour int;
  known boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended('otp:' || p_phone, 0));

  select max(created_at) into last_at from auth_otp_challenge where phone_e164 = p_phone;
  if last_at is not null and p_now < last_at + make_interval(secs => p_resend_seconds) then
    return query select 'TOO_SOON', ceil(extract(epoch from (last_at + make_interval(secs => p_resend_seconds) - p_now)))::int, null::boolean;
    return;
  end if;

  select count(*), min(created_at) into n_phone_hour, oldest_hour from auth_otp_challenge
   where phone_e164 = p_phone and created_at > p_now - interval '1 hour';
  select count(*) into n_phone_day from auth_otp_challenge
   where phone_e164 = p_phone and created_at > p_now - interval '1 day';
  select count(*) into n_ip_hour from auth_otp_challenge
   where ip_hash = p_ip_hash and created_at > p_now - interval '1 hour';

  if n_phone_hour >= p_phone_hour_max then
    return query select 'LIMITED', greatest(1, ceil(extract(epoch from (oldest_hour + interval '1 hour' - p_now)))::int), null::boolean;
    return;
  end if;
  if n_phone_day >= p_phone_day_max then
    return query select 'LIMITED', 3600, null::boolean;
    return;
  end if;
  if n_ip_hour >= p_ip_hour_max then
    return query select 'LIMITED', 600, null::boolean;
    return;
  end if;

  -- Only a number held by an active guardian (active or restricted relationship) in an active school may sign in.
  select exists (
    select 1 from person p
      join tenant t on t.id = p.tenant_id and t.status = 'ACTIVE'
     where p.mobile_e164 = p_phone and p.status = 'ACTIVE'
       and exists (select 1 from guardian_relationship r
                    where r.tenant_id = p.tenant_id and r.guardian_person_id = p.id
                      and r.status in ('ACTIVE', 'RESTRICTED'))
  ) into known;

  -- A new code replaces any earlier one for this number.
  update auth_otp_challenge set consumed_at = p_now where phone_e164 = p_phone and consumed_at is null;
  insert into auth_otp_challenge (phone_e164, ip_hash, code_salt, code_hash, deliverable, created_at, expires_at)
  values (p_phone, p_ip_hash, p_salt, p_hash, known, p_now, p_now + make_interval(secs => p_ttl_seconds));

  return query select 'SENT', p_resend_seconds, known;
end $$;

-- Start checking a submitted code. Counts the attempt BEFORE the comparison and under a row lock, so parallel
-- guesses cannot exceed the attempt limit. Outcomes: CHECK (compare in the application), INVALID, LOCKED.
create or replace function auth_otp_begin_verify(p_phone text, p_now timestamptz, p_max_attempts int)
  returns table (outcome text, challenge_id uuid, code_salt bytea, code_hash bytea, deliverable boolean)
  language plpgsql volatile security definer set search_path = public as $$
declare c auth_otp_challenge%rowtype;
begin
  select * into c from auth_otp_challenge
   where phone_e164 = p_phone order by created_at desc limit 1 for update;
  if not found or c.consumed_at is not null or c.expires_at <= p_now then
    return query select 'INVALID', null::uuid, null::bytea, null::bytea, null::boolean;
    return;
  end if;
  if c.attempts >= p_max_attempts then
    return query select 'LOCKED', null::uuid, null::bytea, null::bytea, null::boolean;
    return;
  end if;
  update auth_otp_challenge set attempts = attempts + 1 where id = c.id;
  return query select 'CHECK', c.id, c.code_salt, c.code_hash, c.deliverable;
end $$;

-- A correct code is single-use: exactly one caller wins.
create or replace function auth_otp_consume(p_challenge uuid, p_now timestamptz)
  returns boolean language plpgsql volatile security definer set search_path = public as $$
declare n int;
begin
  update auth_otp_challenge set consumed_at = p_now
   where id = p_challenge and consumed_at is null and expires_at > p_now and deliverable;
  get diagnostics n = row_count;
  return n = 1;
end $$;

-- ---------------------------------------------------------------------------------------------------------
-- Sessions.

-- Create a session for a verified number and make sure the number has a login identity in each school that
-- holds it on a guardian record. The identity (issuer 'phone', subject = the number) is what the existing
-- request pipeline resolves, so authorization (ADR 0003) is unchanged.
create or replace function auth_session_create(
  p_phone text, p_access_hash bytea, p_refresh_hash bytea, p_device_label text, p_now timestamptz,
  p_access_ttl_seconds int, p_refresh_ttl_seconds int)
  returns uuid language plpgsql volatile security definer set search_path = public as $$
declare sid uuid; n int;
begin
  insert into identity (tenant_id, person_id, issuer, subject, phone_e164, verified_at)
  select p.tenant_id, p.id, 'phone', p_phone, p_phone, p_now
    from person p join tenant t on t.id = p.tenant_id and t.status = 'ACTIVE'
   where p.mobile_e164 = p_phone and p.status = 'ACTIVE'
     and exists (select 1 from guardian_relationship r
                  where r.tenant_id = p.tenant_id and r.guardian_person_id = p.id
                    and r.status in ('ACTIVE', 'RESTRICTED'))
  on conflict (tenant_id, issuer, subject)
  do update set person_id = excluded.person_id, phone_e164 = excluded.phone_e164,
                verified_at = excluded.verified_at, deactivated_at = null;
  get diagnostics n = row_count;
  if n = 0 then
    raise exception 'no guardian record holds this number' using errcode = 'no_data_found';
  end if;

  insert into auth_session (phone_e164, access_hash, refresh_hash, device_label, created_at,
                            access_expires_at, refresh_expires_at, last_seen_at)
  values (p_phone, p_access_hash, p_refresh_hash, left(p_device_label, 80), p_now,
          p_now + make_interval(secs => p_access_ttl_seconds),
          p_now + make_interval(secs => p_refresh_ttl_seconds), p_now)
  returning id into sid;
  return sid;
end $$;

-- Authenticate one request. last_seen_at is refreshed at most every 5 minutes, so reads stay cheap.
create or replace function auth_session_resolve(p_access_hash bytea, p_now timestamptz)
  returns table (issuer text, subject text)
  language plpgsql volatile security definer set search_path = public as $$
declare s auth_session%rowtype;
begin
  select * into s from auth_session
   where access_hash = p_access_hash and revoked_at is null
     and access_expires_at > p_now and refresh_expires_at > p_now;
  if not found then
    return;
  end if;
  if s.last_seen_at < p_now - interval '5 minutes' then
    update auth_session set last_seen_at = p_now where id = s.id;
  end if;
  return query select 'phone'::text, s.phone_e164;
end $$;

-- Exchange a refresh token for a new pair (rotation). Presenting a refresh token that was already replaced
-- means it leaked: the whole session is revoked. Outcomes: OK, INVALID, REUSED.
create or replace function auth_session_refresh(
  p_refresh_hash bytea, p_new_access_hash bytea, p_new_refresh_hash bytea, p_now timestamptz,
  p_access_ttl_seconds int, p_refresh_ttl_seconds int)
  returns table (outcome text)
  language plpgsql volatile security definer set search_path = public as $$
declare s auth_session%rowtype;
begin
  select * into s from auth_session where refresh_hash = p_refresh_hash for update;
  if found then
    if s.revoked_at is not null or s.refresh_expires_at <= p_now then
      return query select 'INVALID'; return;
    end if;
    update auth_session
       set prev_refresh_hash = refresh_hash, refresh_hash = p_new_refresh_hash, access_hash = p_new_access_hash,
           access_expires_at = p_now + make_interval(secs => p_access_ttl_seconds),
           refresh_expires_at = p_now + make_interval(secs => p_refresh_ttl_seconds),
           last_seen_at = p_now
     where id = s.id;
    return query select 'OK'; return;
  end if;

  select * into s from auth_session where prev_refresh_hash = p_refresh_hash for update;
  if found then
    if s.revoked_at is null then
      update auth_session set revoked_at = p_now, revoke_reason = 'REFRESH_REUSE' where id = s.id;
    end if;
    return query select 'REUSED'; return;
  end if;
  return query select 'INVALID';
end $$;

create or replace function auth_session_revoke(p_access_hash bytea, p_now timestamptz)
  returns boolean language plpgsql volatile security definer set search_path = public as $$
declare n int;
begin
  update auth_session set revoked_at = p_now, revoke_reason = 'LOGOUT'
   where access_hash = p_access_hash and revoked_at is null;
  get diagnostics n = row_count;
  return n = 1;
end $$;

-- School office action: end every open session of a guardian's number. The tenant comes from the resolved
-- actor (never from request input) and must actually hold the person; returns how many sessions were ended.
create or replace function auth_sessions_revoke_for_person(p_tenant uuid, p_person uuid, p_now timestamptz)
  returns int language plpgsql volatile security definer set search_path = public as $$
declare phone text; n int;
begin
  select mobile_e164 into phone from person where id = p_person and tenant_id = p_tenant;
  if phone is null then
    return 0;
  end if;
  update auth_session set revoked_at = p_now, revoke_reason = 'ADMIN'
   where phone_e164 = phone and revoked_at is null;
  get diagnostics n = row_count;
  return n;
end $$;

-- Open sessions of a guardian's number, for the office to see before revoking.
create or replace function auth_sessions_for_person(p_tenant uuid, p_person uuid, p_now timestamptz)
  returns table (session_id uuid, device_label text, created_at timestamptz, last_seen_at timestamptz)
  language sql stable security definer set search_path = public as $$
  select s.id, s.device_label, s.created_at, s.last_seen_at
    from auth_session s join person p on p.mobile_e164 = s.phone_e164
   where p.id = p_person and p.tenant_id = p_tenant
     and s.revoked_at is null and s.refresh_expires_at > p_now
   order by s.last_seen_at desc
$$;

-- A guardian's number changes only through the school office (ADR 0009). When it does, the old number stops
-- signing in: its phone identity for this person is closed and, unless another person still holds that number,
-- its sessions are revoked. (Runs as the function owner because the app role cannot touch auth_session.)
create or replace function person_mobile_changed() returns trigger
  language plpgsql security definer set search_path = public as $$
begin
  if old.mobile_e164 is not null and new.mobile_e164 is distinct from old.mobile_e164 then
    update identity set deactivated_at = now()
     where tenant_id = old.tenant_id and person_id = old.id and issuer = 'phone' and deactivated_at is null;
    update auth_session set revoked_at = now(), revoke_reason = 'NUMBER_CHANGED'
     where phone_e164 = old.mobile_e164 and revoked_at is null
       and not exists (select 1 from identity i
                        where i.issuer = 'phone' and i.subject = old.mobile_e164 and i.deactivated_at is null);
  end if;
  return new;
end $$;
create trigger person_mobile_changed after update of mobile_e164 on person
  for each row execute function person_mobile_changed();

revoke all on function
  auth_otp_issue(text, bytea, bytea, bytea, timestamptz, int, int, int, int, int),
  auth_otp_begin_verify(text, timestamptz, int),
  auth_otp_consume(uuid, timestamptz),
  auth_session_create(text, bytea, bytea, text, timestamptz, int, int),
  auth_session_resolve(bytea, timestamptz),
  auth_session_refresh(bytea, bytea, bytea, timestamptz, int, int),
  auth_session_revoke(bytea, timestamptz),
  auth_sessions_revoke_for_person(uuid, uuid, timestamptz),
  auth_sessions_for_person(uuid, uuid, timestamptz),
  person_mobile_changed()
  from public;
grant execute on function
  auth_otp_issue(text, bytea, bytea, bytea, timestamptz, int, int, int, int, int),
  auth_otp_begin_verify(text, timestamptz, int),
  auth_otp_consume(uuid, timestamptz),
  auth_session_create(text, bytea, bytea, text, timestamptz, int, int),
  auth_session_resolve(bytea, timestamptz),
  auth_session_refresh(bytea, bytea, bytea, timestamptz, int, int),
  auth_session_revoke(bytea, timestamptz),
  auth_sessions_revoke_for_person(uuid, uuid, timestamptz),
  auth_sessions_for_person(uuid, uuid, timestamptz)
  to schoomano_app;

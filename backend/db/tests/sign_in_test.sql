-- B4 sign-in invariants (phone OTP and sessions). Run after migrations + dev seed:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f backend/db/tests/sign_in_test.sql
-- Runs as the unprivileged application role, exactly like production; rolls back.
-- Seed: Meera +919800000010 (ACTIVE guardian), Karan +919800000011 (RESTRICTED guardian).

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;

-- Helper: issue with the production limits (30 s resend, 5 per hour, 10 per day, 20 per IP hour, 5 min expiry).
create function pg_temp.issue(p_phone text, p_ip text, p_now timestamptz)
  returns table (outcome text, retry_after_seconds int, deliverable boolean) language sql as $$
  select * from auth_otp_issue(p_phone, sha256(convert_to(p_ip, 'UTF8')), '\x0102'::bytea, '\x0a0b'::bytea,
                               p_now, 300, 30, 5, 10, 20)
$$;

-- S1: the app role has no direct access to the sign-in tables (functions only).
do $$ begin
  begin perform 1 from auth_otp_challenge; raise exception 'S1 failed: challenge table readable';
  exception when insufficient_privilege then null; end;
  begin perform 1 from auth_session; raise exception 'S1 failed: session table readable';
  exception when insufficient_privilege then null; end;
  begin insert into auth_session (phone_e164, access_hash, refresh_hash, created_at, access_expires_at, refresh_expires_at, last_seen_at)
        values ('+919800000010', '\x01', '\x02', now(), now(), now(), now());
        raise exception 'S1 failed: session table writable';
  exception when insufficient_privilege then null; end;
  raise notice 'S1 pass: sign-in tables are reachable only through the functions';
end $$;

-- S2: known and unknown numbers get the same outcome and the same wait; only "deliverable" differs, and it is
-- never shown to the client. Both are stored and counted.
do $$ declare k record; u record; begin
  select * into k from pg_temp.issue('+919800000010', 'ip-a', '2026-11-18 10:00:00+00');
  select * into u from pg_temp.issue('+919123456789', 'ip-a', '2026-11-18 10:00:00+00');
  if k.outcome <> 'SENT' or u.outcome <> 'SENT' then raise exception 'S2 failed: outcomes % / %', k.outcome, u.outcome; end if;
  if k.retry_after_seconds <> u.retry_after_seconds then raise exception 'S2 failed: wait differs'; end if;
  if not k.deliverable or u.deliverable then raise exception 'S2 failed: deliverable flags wrong'; end if;
  raise notice 'S2 pass: known and unknown numbers are indistinguishable except for the (hidden) deliverable flag';
end $$;

-- S3: resend gap of 30 seconds, with the remaining wait reported.
do $$ declare r record; begin
  select * into r from pg_temp.issue('+919800000010', 'ip-a', '2026-11-18 10:00:12+00');
  if r.outcome <> 'TOO_SOON' or r.retry_after_seconds <> 18 then raise exception 'S3 failed: % %', r.outcome, r.retry_after_seconds; end if;
  select * into r from pg_temp.issue('+919800000010', 'ip-a', '2026-11-18 10:00:30+00');
  if r.outcome <> 'SENT' then raise exception 'S3 failed: resend after 30 s refused (%)', r.outcome; end if;
  select * into r from pg_temp.issue('+919123456789', 'ip-a', '2026-11-18 10:00:12+00');
  if r.outcome <> 'TOO_SOON' then raise exception 'S3 failed: unknown number not throttled'; end if;
  raise notice 'S3 pass: resend only after 30 s, identically for unknown numbers';
end $$;

-- S4: caps. Meera has 2 codes this hour (10:00:00, 10:00:30); three more reach 5, the sixth is refused.
do $$ declare r record; t timestamptz := '2026-11-18 10:01:00+00'; i int; begin
  for i in 1..3 loop
    select * into r from pg_temp.issue('+919800000010', 'ip-b', t + (i * interval '31 seconds'));
    if r.outcome <> 'SENT' then raise exception 'S4 failed: code % refused (%)', i + 2, r.outcome; end if;
  end loop;
  select * into r from pg_temp.issue('+919800000010', 'ip-b', t + interval '4 minutes');
  if r.outcome <> 'LIMITED' or r.retry_after_seconds < 1 then raise exception 'S4 failed: hourly cap not enforced (%)', r.outcome; end if;
  -- an hour later the window has moved on
  select * into r from pg_temp.issue('+919800000010', 'ip-b', '2026-11-18 11:02:00+00');
  if r.outcome <> 'SENT' then raise exception 'S4 failed: still limited after the hour (%)', r.outcome; end if;
  raise notice 'S4 pass: per-number hourly cap';
end $$;

do $$ declare r record; i int; begin
  -- per-IP cap: 20 distinct numbers from one address in an hour, the 21st is refused
  for i in 1..20 loop
    select * into r from pg_temp.issue('+9198000' || lpad((20000 + i)::text, 5, '0'), 'ip-flood', '2026-11-18 12:00:00+00');
    if r.outcome <> 'SENT' then raise exception 'S4 failed: ip code % refused (%)', i, r.outcome; end if;
  end loop;
  select * into r from pg_temp.issue('+919800030000', 'ip-flood', '2026-11-18 12:00:00+00');
  if r.outcome <> 'LIMITED' then raise exception 'S4 failed: per-IP cap not enforced (%)', r.outcome; end if;
  select * into r from pg_temp.issue('+919800030000', 'ip-other', '2026-11-18 12:00:00+00');
  if r.outcome <> 'SENT' then raise exception 'S4 failed: other address wrongly limited'; end if;
  raise notice 'S4 pass: per-IP cap';
end $$;

do $$ declare r record; i int; begin
  -- daily cap: 10 per day per number even when the hourly cap is respected
  for i in 0..9 loop
    select * into r from pg_temp.issue('+919876500001', 'ip-day' || i, timestamptz '2026-11-19 00:00:00+00' + (i * interval '2 hours'));
    if r.outcome <> 'SENT' then raise exception 'S4 failed: daily code % refused (%)', i, r.outcome; end if;
  end loop;
  select * into r from pg_temp.issue('+919876500001', 'ip-dayx', '2026-11-19 20:00:00+00');
  if r.outcome <> 'LIMITED' then raise exception 'S4 failed: daily cap not enforced (%)', r.outcome; end if;
  raise notice 'S4 pass: per-number daily cap';
end $$;

-- S5: verification. Attempts are counted before comparing; the fifth wrong guess is the last; expiry and
-- replacement by a newer code both invalidate.
do $$ declare r record; i int; begin
  perform * from pg_temp.issue('+919800000011', 'ip-k', '2026-11-18 13:00:00+00');
  for i in 1..5 loop
    select * into r from auth_otp_begin_verify('+919800000011', '2026-11-18 13:01:00+00', 5);
    if r.outcome <> 'CHECK' or not r.deliverable then raise exception 'S5 failed: attempt % not allowed (%)', i, r.outcome; end if;
  end loop;
  select * into r from auth_otp_begin_verify('+919800000011', '2026-11-18 13:01:30+00', 5);
  if r.outcome <> 'LOCKED' then raise exception 'S5 failed: not locked after 5 attempts (%)', r.outcome; end if;

  -- expired
  perform * from pg_temp.issue('+919800000010', 'ip-m', '2026-11-18 14:00:00+00');
  select * into r from auth_otp_begin_verify('+919800000010', '2026-11-18 14:05:00+00', 5);
  if r.outcome <> 'INVALID' then raise exception 'S5 failed: expired code accepted (%)', r.outcome; end if;

  -- a number that never asked for a code
  select * into r from auth_otp_begin_verify('+919000000000', '2026-11-18 14:00:00+00', 5);
  if r.outcome <> 'INVALID' then raise exception 'S5 failed: no challenge should be INVALID'; end if;

  -- a newer code replaces the older one (the older is consumed)
  perform * from pg_temp.issue('+919800000010', 'ip-m', '2026-11-18 15:00:00+00');
  perform * from pg_temp.issue('+919800000010', 'ip-m', '2026-11-18 15:00:40+00');
  if (select count(*) from auth_otp_begin_verify('+919800000010', '2026-11-18 15:00:50+00', 5) where outcome = 'CHECK') <> 1 then
    raise exception 'S5 failed: newest code not checkable'; end if;
  raise notice 'S5 pass: five attempts then locked; expired, missing and replaced codes are INVALID';
end $$;

-- S6: a correct code is single use, and an undeliverable challenge can never be consumed.
do $$ declare r record; ok1 boolean; ok2 boolean; begin
  perform * from pg_temp.issue('+919800000011', 'ip-s6', '2026-11-18 16:00:00+00');
  select * into r from auth_otp_begin_verify('+919800000011', '2026-11-18 16:00:20+00', 5);
  ok1 := auth_otp_consume(r.challenge_id, '2026-11-18 16:00:21+00');
  ok2 := auth_otp_consume(r.challenge_id, '2026-11-18 16:00:22+00');
  if not ok1 or ok2 then raise exception 'S6 failed: single use violated (% %)', ok1, ok2; end if;
  select * into r from auth_otp_begin_verify('+919800000011', '2026-11-18 16:00:23+00', 5);
  if r.outcome <> 'INVALID' then raise exception 'S6 failed: consumed code still checkable'; end if;

  perform * from pg_temp.issue('+919123456789', 'ip-s6', '2026-11-18 16:10:00+00');
  select * into r from auth_otp_begin_verify('+919123456789', '2026-11-18 16:10:10+00', 5);
  if r.outcome <> 'CHECK' or r.deliverable then raise exception 'S6 failed: unknown number challenge'; end if;
  if auth_otp_consume(r.challenge_id, '2026-11-18 16:10:11+00') then
    raise exception 'S6 failed: undeliverable challenge consumed'; end if;
  raise notice 'S6 pass: single use; unknown numbers can never verify';
end $$;

-- S7: sessions. Creating one gives the number a login identity that the existing pipeline resolves.
do $$ declare sid uuid; r record; n int; begin
  sid := auth_session_create('+919800000010', '\xaa01', '\xbb01', 'Pixel 6a', '2026-11-18 17:00:00+00', 1800, 2592000);
  select count(*) into n from auth_resolve_identity('phone', '+919800000010');
  if n <> 1 then raise exception 'S7 failed: expected 1 school for Meera, got %', n; end if;

  select * into r from auth_session_resolve('\xaa01', '2026-11-18 17:10:00+00');
  if r.issuer is distinct from 'phone' or r.subject is distinct from '+919800000010' then raise exception 'S7 failed: resolve'; end if;
  if exists (select 1 from auth_session_resolve('\xaa01', '2026-11-18 17:31:00+00')) then raise exception 'S7 failed: expired access token accepted'; end if;
  if exists (select 1 from auth_session_resolve('\xdead', '2026-11-18 17:10:00+00')) then raise exception 'S7 failed: unknown token accepted'; end if;

  -- creating again for the same number keeps one identity (no duplicates)
  perform auth_session_create('+919800000010', '\xaa02', '\xbb02', null, '2026-11-18 17:00:00+00', 1800, 2592000);
  select count(*) into n from auth_resolve_identity('phone', '+919800000010');
  if n <> 1 then raise exception 'S7 failed: duplicate identity'; end if;

  begin perform auth_session_create('+919000000000', '\xaa03', '\xbb03', null, '2026-11-18 17:00:00+00', 1800, 2592000);
        raise exception 'S7 failed: session for unknown number';
  exception when no_data_found then null; end;
  raise notice 'S7 pass: session + identity; expiry and unknown tokens rejected; unknown number cannot get a session';
end $$;

-- S8: refresh rotation and reuse detection.
do $$ declare r record; begin
  perform auth_session_create('+919800000011', '\xcc01', '\xdd01', 'Redmi', '2026-11-18 18:00:00+00', 1800, 2592000);
  select * into r from auth_session_refresh('\xdd01', '\xcc02', '\xdd02', '2026-11-18 18:20:00+00', 1800, 2592000);
  if r.outcome <> 'OK' then raise exception 'S8 failed: rotation refused (%)', r.outcome; end if;
  if exists (select 1 from auth_session_resolve('\xcc01', '2026-11-18 18:21:00+00')) then raise exception 'S8 failed: old access token still valid'; end if;
  if not exists (select 1 from auth_session_resolve('\xcc02', '2026-11-18 18:21:00+00')) then raise exception 'S8 failed: new access token invalid'; end if;

  select * into r from auth_session_refresh('\xdd01', '\xcc03', '\xdd03', '2026-11-18 18:22:00+00', 1800, 2592000);
  if r.outcome <> 'REUSED' then raise exception 'S8 failed: reuse not detected (%)', r.outcome; end if;
  if exists (select 1 from auth_session_resolve('\xcc02', '2026-11-18 18:23:00+00')) then raise exception 'S8 failed: session survived refresh reuse'; end if;
  select * into r from auth_session_refresh('\xdd02', '\xcc04', '\xdd04', '2026-11-18 18:24:00+00', 1800, 2592000);
  if r.outcome <> 'INVALID' then raise exception 'S8 failed: revoked session refreshed (%)', r.outcome; end if;
  select * into r from auth_session_refresh('\xeeee', '\xcc05', '\xdd05', '2026-11-18 18:24:00+00', 1800, 2592000);
  if r.outcome <> 'INVALID' then raise exception 'S8 failed: unknown refresh token'; end if;
  raise notice 'S8 pass: refresh rotates; a replayed refresh token revokes the session';
end $$;

-- S9: logout, and expired refresh.
do $$ declare r record; begin
  perform auth_session_create('+919800000010', '\xa901', '\xb901', null, '2026-11-18 19:00:00+00', 1800, 3600);
  if not auth_session_revoke('\xa901', '2026-11-18 19:01:00+00') then raise exception 'S9 failed: logout'; end if;
  if exists (select 1 from auth_session_resolve('\xa901', '2026-11-18 19:02:00+00')) then raise exception 'S9 failed: token valid after logout'; end if;
  if auth_session_revoke('\xa901', '2026-11-18 19:03:00+00') then raise exception 'S9 failed: second logout reported success'; end if;

  perform auth_session_create('+919800000010', '\xa902', '\xb902', null, '2026-11-18 19:00:00+00', 1800, 3600);
  select * into r from auth_session_refresh('\xb902', '\xa903', '\xb903', '2026-11-18 20:00:01+00', 1800, 3600);
  if r.outcome <> 'INVALID' then raise exception 'S9 failed: expired refresh accepted (%)', r.outcome; end if;
  raise notice 'S9 pass: logout; expired refresh token refused';
end $$;

-- S10: the school office ends a guardian's sessions, only inside its own school.
do $$ declare n int; begin
  perform auth_session_create('+919800000010', '\xa101', '\xb101', 'Phone A', '2026-11-18 21:00:00+00', 1800, 2592000);
  perform auth_session_create('+919800000010', '\xa102', '\xb102', 'Phone B', '2026-11-18 21:05:00+00', 1800, 2592000);
  select count(*) into n from auth_sessions_for_person('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', '2026-11-18 21:10:00+00');
  if n < 2 then raise exception 'S10 failed: office list shows % sessions', n; end if;
  if auth_sessions_revoke_for_person('20000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', '2026-11-18 21:10:00+00') <> 0 then
    raise exception 'S10 failed: another school ended our guardian''s sessions'; end if;
  if auth_sessions_revoke_for_person('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', '2026-11-18 21:10:00+00') < 2 then
    raise exception 'S10 failed: sessions not ended'; end if;
  if exists (select 1 from auth_session_resolve('\xa101', '2026-11-18 21:11:00+00')) then raise exception 'S10 failed: token valid after admin revoke'; end if;
  raise notice 'S10 pass: office revoke is tenant-checked and effective at once';
end $$;

-- S11: changing a guardian's number closes the old number's login and sessions (school-controlled change).
do $$ declare n int; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  perform auth_session_create('+919800000011', '\xa201', '\xb201', 'Karan phone', '2026-11-18 22:00:00+00', 1800, 2592000);
  update person set mobile_e164 = '+919800099011' where id = '13000000-0000-4000-8000-000000000011';
  select count(*) into n from auth_resolve_identity('phone', '+919800000011');
  if n <> 0 then raise exception 'S11 failed: old number still resolves to a school'; end if;
  if exists (select 1 from auth_session_resolve('\xa201', '2026-11-18 22:01:00+00')) then raise exception 'S11 failed: old number session survived'; end if;
  -- the new number is a fresh sign-in
  perform * from pg_temp.issue('+919800099011', 'ip-s11', '2026-11-18 22:02:00+00');
  if not (select deliverable from auth_otp_begin_verify('+919800099011', '2026-11-18 22:02:10+00', 5)) then
    raise exception 'S11 failed: new number not deliverable'; end if;
  raise notice 'S11 pass: number change ends the old number''s sign-in';
end $$;

-- S12: a guardian with only an ended relationship cannot sign in (number is not on a live guardian record).
do $$ declare r record; begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  update guardian_relationship set status = 'ENDED', ended_at = now()
   where guardian_person_id = '13000000-0000-4000-8000-000000000011';
  select * into r from pg_temp.issue('+919800099011', 'ip-s12', '2026-11-18 23:00:00+00');
  if r.outcome <> 'SENT' or r.deliverable then raise exception 'S12 failed: ended guardian deliverable (% %)', r.outcome, r.deliverable; end if;
  begin perform auth_session_create('+919800099011', '\xa301', '\xb301', null, '2026-11-18 23:00:00+00', 1800, 2592000);
        raise exception 'S12 failed: session for an ended guardian';
  exception when no_data_found then null; end;
  raise notice 'S12 pass: ended guardians cannot sign in';
end $$;

rollback;  -- leave the seeded database unchanged
\echo 'ALL SIGN-IN TESTS PASSED'

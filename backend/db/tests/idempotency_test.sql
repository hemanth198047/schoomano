-- B36 idempotency keys. Run as the schema owner after migrations; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
do $$ declare s1 text := repeat('a', 64); s2 text := repeat('b', 64); r record; begin
  -- I1: the first call goes ahead, a repeat while it runs is busy, a repeat after it finishes replays the stored answer.
  select * into r from idem_begin(s1, 'key-0000001', '\x01'); if r.outcome <> 'NEW' then raise exception 'I1 failed: first call %', r.outcome; end if;
  select * into r from idem_begin(s1, 'key-0000001', '\x01'); if r.outcome <> 'BUSY' then raise exception 'I1 failed: repeat while running %', r.outcome; end if;
  if not idem_finish(s1, 'key-0000001', '\x01', 201, '{"id":"x"}', 'application/json') then raise exception 'I1 failed: finish'; end if;
  select * into r from idem_begin(s1, 'key-0000001', '\x01');
  if r.outcome <> 'REPLAY' or r.r_status <> 201 or r.r_body <> '{"id":"x"}' or r.r_content_type <> 'application/json' then raise exception 'I1 failed: replay %', r.outcome; end if;
  if idem_finish(s1, 'key-0000001', '\x01', 200, 'other', 'text/plain') then raise exception 'I1 failed: a finished record was overwritten'; end if;
  raise notice 'I1 pass: new, busy, replay';

  -- I2: a different request under the same key is refused, and another caller's key is separate.
  select * into r from idem_begin(s1, 'key-0000001', '\x02'); if r.outcome <> 'MISMATCH' then raise exception 'I2 failed: mismatch %', r.outcome; end if;
  select * into r from idem_begin(s2, 'key-0000001', '\x02'); if r.outcome <> 'NEW' then raise exception 'I2 failed: another caller blocked %', r.outcome; end if;
  select * into r from idem_begin(s2, 'key-0000001', '\x01'); if r.outcome <> 'MISMATCH' then raise exception 'I2 failed: callers share a key'; end if;
  raise notice 'I2 pass: mismatch and separation';

  -- I3: an abandoned call can be retried; only the owner of the fingerprint can abandon or finish it.
  select * into r from idem_begin(s1, 'key-0000002', '\x05'); if r.outcome <> 'NEW' then raise exception 'I3 failed'; end if;
  perform idem_abandon(s1, 'key-0000002', '\x06');
  select * into r from idem_begin(s1, 'key-0000002', '\x05'); if r.outcome <> 'BUSY' then raise exception 'I3 failed: abandoned by the wrong request'; end if;
  perform idem_abandon(s1, 'key-0000002', '\x05');
  select * into r from idem_begin(s1, 'key-0000002', '\x05'); if r.outcome <> 'NEW' then raise exception 'I3 failed: retry after abandon %', r.outcome; end if;
  begin select * into r from api_idempotency limit 1; raise exception 'I3 failed: table readable';
  exception when insufficient_privilege then null; end;
  begin insert into api_idempotency (scope, key, fingerprint, state) values (s1, 'key-0000009', '\x01', 'IN_FLIGHT'); raise exception 'I3 failed: table writable';
  exception when insufficient_privilege then null; end;
  begin perform idem_begin('short', 'key-0000003', '\x01'); raise exception 'I3 failed: bad scope accepted';
  exception when check_violation then null; end;
  raise notice 'I3 pass: abandon, retry and privileges';
end $$;

-- I4: old records and stuck calls expire.
reset role;
update api_idempotency set created_at = clock_timestamp() - interval '3 minutes' where key = 'key-0000002';
update api_idempotency set created_at = clock_timestamp() - interval '25 hours' where key = 'key-0000001' and scope = repeat('a', 64);
set local role schoomano_app;
do $$ declare r record; begin
  select * into r from idem_begin(repeat('a', 64), 'key-0000002', '\x05'); if r.outcome <> 'NEW' then raise exception 'I4 failed: stuck call not released %', r.outcome; end if;
  select * into r from idem_begin(repeat('a', 64), 'key-0000001', '\x09'); if r.outcome <> 'NEW' then raise exception 'I4 failed: old record kept %', r.outcome; end if;
  raise notice 'I4 pass: expiry';
end $$;
rollback;

-- B36 / M27: idempotency keys. A client that repeats a state-changing request with the same Idempotency-Key gets the first
-- response back instead of doing the work twice. A record belongs to one caller (a hash of who they are and which school
-- they act in), lives 24 hours, and stores only successful JSON answers. The table is reachable only through the functions
-- below, so one caller can never read or reuse another's key.

create table api_idempotency (
  scope        text  not null check (scope ~ '^[0-9a-f]{64}$'),
  key          text  not null check (key ~ '^[A-Za-z0-9._:-]{8,100}$'),
  fingerprint  bytea not null,
  state        text  not null check (state in ('IN_FLIGHT', 'DONE')),
  status       integer check (status between 200 and 299),
  body         text,
  content_type text,
  created_at   timestamptz not null default clock_timestamp(),
  primary key (scope, key),
  check ((state = 'DONE') = (status is not null))
);
create index api_idempotency_by_age on api_idempotency (created_at);
revoke all on api_idempotency from public;
revoke all on api_idempotency from schoomano_app;

-- NEW: go ahead and do the work. REPLAY: answer with the stored response. MISMATCH: the key was used for a different
-- request. BUSY: the first request is still running. A record left in flight for two minutes is treated as abandoned.
create function idem_begin(p_scope text, p_key text, p_fingerprint bytea)
  returns table (outcome text, r_status integer, r_body text, r_content_type text)
  language plpgsql security definer set search_path = public as $$
declare rec api_idempotency%rowtype; n integer;
begin
  delete from api_idempotency where (scope, key) in
    (select i.scope, i.key from api_idempotency i where i.created_at < clock_timestamp() - interval '24 hours' order by i.created_at limit 50);
  delete from api_idempotency where scope = p_scope and key = p_key and state = 'IN_FLIGHT' and created_at < clock_timestamp() - interval '2 minutes';
  insert into api_idempotency (scope, key, fingerprint, state) values (p_scope, p_key, p_fingerprint, 'IN_FLIGHT') on conflict do nothing;
  get diagnostics n = row_count;
  if n = 1 then
    return query select 'NEW'::text, null::integer, null::text, null::text;
    return;
  end if;
  select * into rec from api_idempotency where scope = p_scope and key = p_key;
  if not found then
    return query select 'BUSY'::text, null::integer, null::text, null::text;
  elsif rec.fingerprint <> p_fingerprint then
    return query select 'MISMATCH'::text, null::integer, null::text, null::text;
  elsif rec.state = 'IN_FLIGHT' then
    return query select 'BUSY'::text, null::integer, null::text, null::text;
  else
    return query select 'REPLAY'::text, rec.status, rec.body, rec.content_type;
  end if;
end $$;
revoke all on function idem_begin(text, text, bytea) from public;
grant execute on function idem_begin(text, text, bytea) to schoomano_app;

create function idem_finish(p_scope text, p_key text, p_fingerprint bytea, p_status integer, p_body text, p_content_type text) returns boolean
  language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  update api_idempotency set state = 'DONE', status = p_status, body = p_body, content_type = p_content_type
   where scope = p_scope and key = p_key and fingerprint = p_fingerprint and state = 'IN_FLIGHT';
  get diagnostics n = row_count;
  return n = 1;
end $$;
revoke all on function idem_finish(text, text, bytea, integer, text, text) from public;
grant execute on function idem_finish(text, text, bytea, integer, text, text) to schoomano_app;

-- The request failed or its answer cannot be kept: forget it so the client can try again with the same key.
create function idem_abandon(p_scope text, p_key text, p_fingerprint bytea) returns void
  language sql security definer set search_path = public as $$
  delete from api_idempotency where scope = p_scope and key = p_key and fingerprint = p_fingerprint and state = 'IN_FLIGHT'
$$;
revoke all on function idem_abandon(text, text, bytea) from public;
grant execute on function idem_abandon(text, text, bytea) to schoomano_app;

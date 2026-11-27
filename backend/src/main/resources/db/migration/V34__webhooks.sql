-- B34 / M27: webhooks. A group administrator registers an https address and the event types it wants. Each matching outbox
-- event becomes one delivery per endpoint, sent signed (HMAC-SHA256) and retried with growing delays; an endpoint that keeps
-- failing is switched off. Events carry ids only, never names. The signing secret is readable only by the dispatcher
-- functions, never by the application role directly. Endpoints belong to the whole school, because events are not campus-bound.

alter table outbox_event add column webhooked_at timestamptz;
update outbox_event set webhooked_at = now();          -- history is not replayed to endpoints that do not exist yet
create index outbox_unwebhooked_idx on outbox_event (occurred_at) where webhooked_at is null;

create table webhook_endpoint (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references tenant(id),
  name                 text not null check (char_length(btrim(name)) between 3 and 80),
  url                  text not null check (char_length(url) <= 500 and url ~ '^https://[^/[:space:]]+(/[^[:space:]]*)?$'),
  secret               text not null,
  event_types          text[] not null check (cardinality(event_types) between 1 and 20),
  active               boolean not null default true,
  consecutive_failures integer not null default 0,
  created_by           uuid,
  created_at           timestamptz not null default now(),
  disabled_at          timestamptz,
  disabled_reason      text,
  unique (tenant_id, id)
);
create index webhook_endpoint_by_tenant on webhook_endpoint (tenant_id, created_at desc);

create table webhook_delivery (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  endpoint_id     uuid not null references webhook_endpoint(id),
  outbox_event_id uuid not null references outbox_event(id),
  status          text not null default 'PENDING' check (status in ('PENDING', 'SENT', 'FAILED')),
  attempts        integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_status     integer,
  last_error      text,
  created_at      timestamptz not null default now(),
  sent_at         timestamptz,
  unique (endpoint_id, outbox_event_id)
);
create index webhook_delivery_due on webhook_delivery (next_attempt_at) where status = 'PENDING';
create index webhook_delivery_by_endpoint on webhook_delivery (endpoint_id, created_at desc);

do $$
declare t text;
begin
  foreach t in array array['webhook_endpoint', 'webhook_delivery']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

-- The secret column is left out on purpose: the application can write it once and never read it back.
grant select (id, tenant_id, name, url, event_types, active, consecutive_failures, created_by, created_at, disabled_at, disabled_reason)
  on webhook_endpoint to schoomano_app;
grant insert on webhook_endpoint to schoomano_app;
grant update (active, disabled_at, disabled_reason) on webhook_endpoint to schoomano_app;
grant select on webhook_delivery to schoomano_app;

-- One delivery per matching, active endpoint that existed when the event happened. Safe from several instances.
create function webhook_fan_out(p_limit integer) returns integer
  language plpgsql security definer set search_path = public as $$
declare ev outbox_event%rowtype; n integer := 0;
begin
  for ev in select * from outbox_event where webhooked_at is null order by occurred_at limit p_limit for update skip locked
  loop
    insert into webhook_delivery (tenant_id, endpoint_id, outbox_event_id)
    select e.tenant_id, e.id, ev.id from webhook_endpoint e
     where e.tenant_id = ev.tenant_id and e.active and e.created_at <= ev.occurred_at and ev.event_type = any (e.event_types)
    on conflict do nothing;
    update outbox_event set webhooked_at = now() where id = ev.id;
    n := n + 1;
  end loop;
  return n;
end $$;
revoke all on function webhook_fan_out(integer) from public;
grant execute on function webhook_fan_out(integer) to schoomano_app;

-- Deliveries that are due. A claimed delivery is leased for two minutes so two instances never send the same one.
create function webhook_claim_due(p_limit integer)
  returns table (delivery_id uuid, url text, secret text, event_id uuid, event_type text, occurred_at timestamptz,
                 tenant_id uuid, aggregate_type text, aggregate_id text, payload jsonb, attempt integer)
  language plpgsql security definer set search_path = public as $$
begin
  return query
  with due as (
    select d.id from webhook_delivery d join webhook_endpoint e on e.id = d.endpoint_id
     where d.status = 'PENDING' and d.next_attempt_at <= now() and e.active
     order by d.next_attempt_at limit p_limit for update of d skip locked
  ), leased as (
    update webhook_delivery d set attempts = d.attempts + 1, next_attempt_at = now() + interval '2 minutes'
      from due where d.id = due.id returning d.id, d.endpoint_id, d.outbox_event_id, d.attempts, d.tenant_id
  )
  select l.id, e.url, e.secret, ev.id, ev.event_type, ev.occurred_at, l.tenant_id, ev.aggregate_type, ev.aggregate_id, ev.payload, l.attempts
    from leased l join webhook_endpoint e on e.id = l.endpoint_id join outbox_event ev on ev.id = l.outbox_event_id;
end $$;
revoke all on function webhook_claim_due(integer) from public;
grant execute on function webhook_claim_due(integer) to schoomano_app;

-- Records the outcome. A failure is retried after 1, 4, 16, 64 minutes and so on (at most 6 hours), up to six attempts.
-- Ten deliveries in a row that end failed switch the endpoint off.
create function webhook_mark(p_delivery uuid, p_status integer, p_error text) returns void
  language plpgsql security definer set search_path = public as $$
declare d webhook_delivery%rowtype; ok boolean := p_status between 200 and 299;
begin
  select * into d from webhook_delivery where id = p_delivery for update;
  if not found or d.status <> 'PENDING' then return; end if;
  if ok then
    update webhook_delivery set status = 'SENT', sent_at = now(), last_status = p_status, last_error = null where id = d.id;
    update webhook_endpoint set consecutive_failures = 0 where id = d.endpoint_id;
  elsif d.attempts >= 6 then
    update webhook_delivery set status = 'FAILED', last_status = p_status, last_error = left(coalesce(p_error, 'failed'), 300) where id = d.id;
    update webhook_endpoint set consecutive_failures = consecutive_failures + 1 where id = d.endpoint_id;
    update webhook_endpoint set active = false, disabled_at = now(), disabled_reason = 'Too many failed deliveries'
     where id = d.endpoint_id and active and consecutive_failures >= 10;
  else
    update webhook_delivery
       set last_status = p_status, last_error = left(coalesce(p_error, 'failed'), 300),
           next_attempt_at = now() + make_interval(secs => least(power(4, d.attempts) * 15, 21600))
     where id = d.id;
  end if;
end $$;
revoke all on function webhook_mark(uuid, integer, text) from public;
grant execute on function webhook_mark(uuid, integer, text) to schoomano_app;

-- A test event to one endpoint, whatever it subscribed to, so the receiver can be checked.
create function webhook_ping(p_endpoint uuid) returns uuid
  language plpgsql security definer set search_path = public as $$
declare t uuid := app_current_tenant(); ev uuid; d uuid;
begin
  if t is null or not exists (select 1 from webhook_endpoint where id = p_endpoint and tenant_id = t and active) then return null; end if;
  insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload, published_at, webhooked_at)
  values (t, 'webhook', p_endpoint::text, 'webhook.ping', '{}'::jsonb, now(), now()) returning id into ev;
  insert into webhook_delivery (tenant_id, endpoint_id, outbox_event_id) values (t, p_endpoint, ev) returning id into d;
  return d;
end $$;
revoke all on function webhook_ping(uuid) from public;
grant execute on function webhook_ping(uuid) to schoomano_app;

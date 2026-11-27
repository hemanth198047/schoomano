-- B34 webhooks. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

insert into webhook_endpoint (id, tenant_id, name, url, secret, event_types) values
  ('1f000000-0000-4000-8000-0000000000a1', '10000000-0000-4000-8000-000000000001', 'Library', 'https://a.example.test/hook', 'whsec_a', '{attendance.absent}'),
  ('1f000000-0000-4000-8000-0000000000b1', '10000000-0000-4000-8000-000000000001', 'Config watcher', 'https://b.example.test/hook', 'whsec_b', '{config.published}'),
  ('1f000000-0000-4000-8000-0000000000c1', '10000000-0000-4000-8000-000000000001', 'Switched off', 'https://c.example.test/hook', 'whsec_c', '{attendance.absent}'),
  ('1f000000-0000-4000-8000-0000000000d1', '10000000-0000-4000-8000-000000000001', 'Imports', 'https://d.example.test/hook', 'whsec_d', '{import.committed}');
update webhook_endpoint set active = false, disabled_at = now(), disabled_reason = 'test' where id = '1f000000-0000-4000-8000-0000000000c1';
insert into outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, occurred_at) values
  ('1e100000-0000-4000-8000-0000000000e1', '10000000-0000-4000-8000-000000000001', 'attendance', 'x', 'attendance.absent', '{"studentId":"s"}', now()),
  ('1e100000-0000-4000-8000-0000000000e2', '10000000-0000-4000-8000-000000000001', 'attendance', 'x', 'attendance.absent', '{}', now() - interval '1 day'),
  ('1e100000-0000-4000-8000-0000000000e3', '10000000-0000-4000-8000-000000000001', 'import', 'y', 'import.committed', '{}', now());

-- W1: an event reaches only active endpoints that asked for it and existed at the time, once.
do $$ begin
  perform webhook_fan_out(1000);
  perform webhook_fan_out(1000);
  if (select count(*) from webhook_delivery where endpoint_id = '1f000000-0000-4000-8000-0000000000a1') <> 1 then raise exception 'W1 failed: subscribed endpoint'; end if;
  if (select count(*) from webhook_delivery where endpoint_id = '1f000000-0000-4000-8000-0000000000a1' and outbox_event_id = '1e100000-0000-4000-8000-0000000000e2') <> 0 then
    raise exception 'W1 failed: event from before the endpoint existed was sent'; end if;
  if (select count(*) from webhook_delivery where endpoint_id in ('1f000000-0000-4000-8000-0000000000b1', '1f000000-0000-4000-8000-0000000000c1')) <> 0 then
    raise exception 'W1 failed: unsubscribed or inactive endpoint got a delivery'; end if;
  if (select count(*) from webhook_delivery where endpoint_id = '1f000000-0000-4000-8000-0000000000d1') <> 1 then raise exception 'W1 failed: second endpoint'; end if;
  begin perform secret from webhook_endpoint; raise exception 'W1 failed: secret readable';
  exception when insufficient_privilege then null; end;
  begin insert into webhook_endpoint (tenant_id, name, url, secret, event_types) values ('10000000-0000-4000-8000-000000000001', 'Plain', 'http://x.example.test', 's', '{a.b}');
    raise exception 'W1 failed: plain http accepted';
  exception when check_violation then null; end;
  raise notice 'W1 pass: events reach the right endpoints once';
end $$;

-- W2: a claimed delivery is leased; failures back off and end FAILED after six attempts; ten failures in a row switch the endpoint off.
reset role;
update webhook_endpoint set consecutive_failures = 9 where id = '1f000000-0000-4000-8000-0000000000a1';
do $$ declare d uuid; i integer; begin
  select delivery_id into d from webhook_claim_due(100) where url = 'https://a.example.test/hook';
  if d is null then raise exception 'W2 failed: nothing claimed'; end if;
  if (select count(*) from webhook_claim_due(100) where url = 'https://a.example.test/hook') <> 0 then raise exception 'W2 failed: claimed twice'; end if;
  perform webhook_mark(d, 500, 'boom');
  if (select status from webhook_delivery where id = d) <> 'PENDING' or (select next_attempt_at from webhook_delivery where id = d) <= now() + interval '30 seconds' then
    raise exception 'W2 failed: no back-off'; end if;
  for i in 2..6 loop
    update webhook_delivery set next_attempt_at = now() where id = d;
    if (select count(*) from webhook_claim_due(100) where delivery_id = d and attempt = i) <> 1 then raise exception 'W2 failed: attempt % not claimed', i; end if;
    perform webhook_mark(d, 503, 'down');
  end loop;
  if (select status from webhook_delivery where id = d) <> 'FAILED' then raise exception 'W2 failed: not failed after six attempts'; end if;
  update webhook_delivery set next_attempt_at = now() where id = d;
  if (select count(*) from webhook_claim_due(100) where delivery_id = d) <> 0 then raise exception 'W2 failed: failed delivery claimed again'; end if;
  if (select active from webhook_endpoint where id = '1f000000-0000-4000-8000-0000000000a1') then raise exception 'W2 failed: endpoint not switched off'; end if;
  raise notice 'W2 pass: retries, failure and switching off';
end $$;

-- W3: a success clears the failure count; ping reaches one endpoint of the caller's own school only.
do $$ declare d uuid; p uuid; begin
  update webhook_endpoint set consecutive_failures = 4 where id = '1f000000-0000-4000-8000-0000000000d1';
  update webhook_delivery set next_attempt_at = now() where endpoint_id = '1f000000-0000-4000-8000-0000000000d1';   -- the earlier claim leased it too
  select delivery_id into d from webhook_claim_due(100) where url = 'https://d.example.test/hook';
  perform webhook_mark(d, 204, null);
  if (select status from webhook_delivery where id = d) <> 'SENT' or (select consecutive_failures from webhook_endpoint where id = '1f000000-0000-4000-8000-0000000000d1') <> 0 then
    raise exception 'W3 failed: success not recorded'; end if;
  perform webhook_mark(d, 500, 'late');
  if (select status from webhook_delivery where id = d) <> 'SENT' then raise exception 'W3 failed: sent delivery changed'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if webhook_ping('1f000000-0000-4000-8000-0000000000d1') is not null then raise exception 'W3 failed: pinged another school''s endpoint'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  if webhook_ping('1f000000-0000-4000-8000-0000000000a1') is not null then raise exception 'W3 failed: pinged a switched-off endpoint'; end if;
  p := webhook_ping('1f000000-0000-4000-8000-0000000000d1');
  if p is null or (select ev.event_type from webhook_delivery x join outbox_event ev on ev.id = x.outbox_event_id where x.id = p) <> 'webhook.ping' then raise exception 'W3 failed: ping'; end if;
  raise notice 'W3 pass: success, ping and isolation';
end $$;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
do $$ begin
  if (select count(*) from webhook_endpoint) <> 0 or (select count(*) from webhook_delivery) <> 0 then raise exception 'W3 failed: another school sees endpoints'; end if;
  raise notice 'W3b pass: tenants are isolated';
end $$;
rollback;

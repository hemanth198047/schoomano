-- B39 / M27: a webhook signing secret can be replaced without a gap. The old secret keeps working for a day, and during that
-- time every delivery carries a signature for each, so the receiver can switch over at its own pace. As before, the
-- application can write secrets but never read them back; the secrets themselves are stored encrypted by the application.

alter table webhook_endpoint
  add column previous_secret text,
  add column previous_secret_expires_at timestamptz,
  add column secret_rotated_at timestamptz;
grant select (previous_secret_expires_at, secret_rotated_at) on webhook_endpoint to schoomano_app;

-- Swaps in the new secret. The current one becomes the previous one, valid for p_grace. Nothing happens to a switched-off endpoint.
create function webhook_rotate(p_endpoint uuid, p_new_secret text, p_grace interval) returns boolean
  language plpgsql security definer set search_path = public as $$
declare t uuid := app_current_tenant();
begin
  if t is null or p_grace < interval '0' or p_grace > interval '7 days' then return false; end if;
  update webhook_endpoint
     set previous_secret = secret, previous_secret_expires_at = now() + p_grace,
         secret = p_new_secret, secret_rotated_at = now()
   where id = p_endpoint and tenant_id = t and active;
  return found;
end $$;
revoke all on function webhook_rotate(uuid, text, interval) from public;
grant execute on function webhook_rotate(uuid, text, interval) to schoomano_app;

-- Same as before, plus the previous secret while it is still valid.
drop function webhook_claim_due(integer);
create function webhook_claim_due(p_limit integer)
  returns table (delivery_id uuid, url text, secret text, event_id uuid, event_type text, occurred_at timestamptz,
                 tenant_id uuid, aggregate_type text, aggregate_id text, payload jsonb, attempt integer, previous_secret text)
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
  select l.id, e.url, e.secret, ev.id, ev.event_type, ev.occurred_at, l.tenant_id, ev.aggregate_type, ev.aggregate_id, ev.payload, l.attempts,
         case when e.previous_secret_expires_at > now() then e.previous_secret end
    from leased l join webhook_endpoint e on e.id = l.endpoint_id join outbox_event ev on ev.id = l.outbox_event_id;
end $$;
revoke all on function webhook_claim_due(integer) from public;
grant execute on function webhook_claim_due(integer) to schoomano_app;

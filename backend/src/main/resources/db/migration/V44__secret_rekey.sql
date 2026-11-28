-- B49 / M27: the encryption key can be changed. The application re-seals every stored webhook secret under the new key,
-- one row at a time, and only if the row has not changed since it was read (so two instances, or a rotation of a webhook
-- secret at the same moment, cannot overwrite each other).
create function webhook_secrets_all() returns table (endpoint_id uuid, secret text, previous_secret text)
  language sql security definer set search_path = public as $$
  select id, secret, previous_secret from webhook_endpoint order by id
$$;
revoke all on function webhook_secrets_all() from public;
grant execute on function webhook_secrets_all() to schoomano_app;

create function webhook_secret_rekey(p_id uuid, p_old text, p_old_previous text, p_new text, p_new_previous text) returns boolean
  language plpgsql security definer set search_path = public as $$
begin
  update webhook_endpoint set secret = p_new, previous_secret = p_new_previous
   where id = p_id and secret = p_old and previous_secret is not distinct from p_old_previous;
  return found;
end $$;
revoke all on function webhook_secret_rekey(uuid, text, text, text, text) from public;
grant execute on function webhook_secret_rekey(uuid, text, text, text, text) to schoomano_app;

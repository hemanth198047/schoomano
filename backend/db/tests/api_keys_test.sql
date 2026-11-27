-- B33 API keys. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- K1: creating a key makes a service identity with one read-only grant that ends when the key does.
do $$ declare k uuid; begin
  k := api_key_create('12000000-0000-4000-8000-000000000001', 'Test key', '\x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20', 'abcd',
    now() + interval '30 days', '13000000-0000-4000-8000-000000000001');
  if (select count(*) from identity where issuer = 'apikey' and subject = k::text and deactivated_at is null) <> 1 then raise exception 'K1 failed: no identity'; end if;
  if (select count(*) from role_grant g join api_key a on a.person_id = g.person_id where a.id = k and g.role_key = 'integration_reader' and g.valid_to is not null) <> 1 then
    raise exception 'K1 failed: grant'; end if;
  if (select count(*) from api_key_resolve('\x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20', now())) <> 1 then raise exception 'K1 failed: resolve'; end if;
  if (select count(*) from api_key_resolve('\x00', now())) <> 0 then raise exception 'K1 failed: wrong hash resolved'; end if;
  begin perform api_key_create('12000000-0000-4000-8000-000000000001', 'Too long', '\x99', 'abcd', now() + interval '800 days', null);
    raise exception 'K1 failed: lifetime above two years accepted';
  exception when check_violation then null; end;
  raise notice 'K1 pass: service identity with an expiring read-only grant';
end $$;

-- K2: revoking stops the key at once, and the key can no longer sign in or hold a role.
do $$ declare k uuid; begin
  select id into k from api_key where name = 'Test key';
  if not api_key_revoke(k, '13000000-0000-4000-8000-000000000001') then raise exception 'K2 failed: not revoked'; end if;
  if api_key_revoke(k, null) then raise exception 'K2 failed: revoked twice'; end if;
  if (select count(*) from api_key_resolve('\x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20', now())) <> 0 then raise exception 'K2 failed: still resolves'; end if;
  if (select count(*) from identity where issuer = 'apikey' and subject = k::text and deactivated_at is null) <> 0 then raise exception 'K2 failed: identity live'; end if;
  if (select count(*) from role_grant g join api_key a on a.person_id = g.person_id where a.id = k and (g.valid_to is null or g.valid_to > now() + interval '1 second')) <> 0 then
    raise exception 'K2 failed: grant live'; end if;
  raise notice 'K2 pass: revoked keys are dead at once';
end $$;

-- K3: an expired key does not resolve; another school sees and revokes nothing.
do $$ declare k uuid; begin
  k := api_key_create('12000000-0000-4000-8000-000000000001', 'Second key', '\xaa01', 'wxyz', now() + interval '1 day', null);
  if (select count(*) from api_key_resolve('\xaa01', now() + interval '2 days')) <> 0 then raise exception 'K3 failed: expired key resolved'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from api_key) <> 0 then raise exception 'K3 failed: leak'; end if;
  if api_key_revoke(k, null) then raise exception 'K3 failed: revoked another school''s key'; end if;
  begin perform api_key_create('12000000-0000-4000-8000-000000000001', 'Cross', '\xbb01', 'cccc', now() + interval '1 day', null);
    raise exception 'K3 failed: key made on another school''s campus';
  exception when foreign_key_violation then null; end;
  raise notice 'K3 pass: expiry and isolation';
end $$;
rollback;

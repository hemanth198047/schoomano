-- B33 / M27: API keys for integrations. A key is a service identity of one campus with a read-only role. The secret is
-- shown once and stored only as a SHA-256 hash. A key expires on a date chosen at creation (at most two years) and can be
-- revoked at once. Creating and revoking run through functions so the person, identity and role grant stay in step.

create table api_key (
  id           uuid primary key,
  tenant_id    uuid not null references tenant(id),
  campus_id    uuid not null,
  person_id    uuid not null,
  name         text not null check (char_length(btrim(name)) between 3 and 80),
  token_hash   bytea not null unique,
  token_hint   text not null check (char_length(token_hint) = 4),
  created_by   uuid,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null,
  revoked_at   timestamptz,
  last_used_at timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, person_id) references person(tenant_id, id),
  check (expires_at > created_at and expires_at <= created_at + interval '731 days')
);
create index api_key_by_campus on api_key (tenant_id, campus_id, created_at desc);

alter table api_key enable row level security;
alter table api_key force row level security;
create policy tenant_isolation on api_key using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
grant select on api_key to schoomano_app;

create function api_key_create(p_campus uuid, p_name text, p_hash bytea, p_hint text, p_expires_at timestamptz, p_created_by uuid)
  returns uuid language plpgsql security definer set search_path = public as $$
declare t uuid := app_current_tenant(); k uuid := gen_random_uuid(); p uuid := gen_random_uuid();
begin
  if t is null then raise exception 'no school selected' using errcode = 'check_violation'; end if;
  if not exists (select 1 from campus where tenant_id = t and id = p_campus) then
    raise exception 'unknown campus' using errcode = 'foreign_key_violation';
  end if;
  insert into person (id, tenant_id, school_ref, given_name, family_name, created_by)
    values (p, t, 'KEY-' || substr(replace(k::text, '-', ''), 1, 10), left(btrim(p_name), 80), '(API key)', p_created_by);
  insert into identity (tenant_id, person_id, issuer, subject) values (t, p, 'apikey', k::text);
  insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, valid_to, granted_by, reason)
    values (t, p, 'integration_reader', 'CAMPUS', p_campus, p_expires_at, p_created_by, 'API key');
  insert into api_key (id, tenant_id, campus_id, person_id, name, token_hash, token_hint, created_by, expires_at)
    values (k, t, p_campus, p, btrim(p_name), p_hash, p_hint, p_created_by, p_expires_at);
  return k;
end $$;
revoke all on function api_key_create(uuid, text, bytea, text, timestamptz, uuid) from public;
grant execute on function api_key_create(uuid, text, bytea, text, timestamptz, uuid) to schoomano_app;

create function api_key_revoke(p_id uuid, p_by uuid) returns boolean language plpgsql security definer set search_path = public as $$
declare t uuid := app_current_tenant(); k api_key%rowtype;
begin
  if t is null then raise exception 'no school selected' using errcode = 'check_violation'; end if;
  select * into k from api_key where tenant_id = t and id = p_id and revoked_at is null for update;
  if not found then return false; end if;
  update api_key set revoked_at = now() where id = k.id;
  update identity set deactivated_at = now() where tenant_id = t and person_id = k.person_id and deactivated_at is null;
  update role_grant set valid_to = greatest(now(), valid_from + interval '1 millisecond')
   where tenant_id = t and person_id = k.person_id and (valid_to is null or valid_to > now());
  return true;
end $$;
revoke all on function api_key_revoke(uuid, uuid) from public;
grant execute on function api_key_revoke(uuid, uuid) to schoomano_app;

-- Used before any school is known, exactly like auth_session_resolve: the hash is the lookup.
create function api_key_resolve(p_hash bytea, p_now timestamptz)
  returns table (issuer text, subject text)
  language plpgsql volatile security definer set search_path = public as $$
declare k api_key%rowtype;
begin
  select * into k from api_key where token_hash = p_hash and revoked_at is null and expires_at > p_now;
  if not found then return; end if;
  if k.last_used_at is null or k.last_used_at < p_now - interval '5 minutes' then
    update api_key set last_used_at = p_now where id = k.id;
  end if;
  return query select 'apikey'::text, k.id::text;
end $$;
revoke all on function api_key_resolve(bytea, timestamptz) from public;
grant execute on function api_key_resolve(bytea, timestamptz) to schoomano_app;

-- Tenant isolation is enforced by PostgreSQL itself, not only by application code.
-- The app sets `app.tenant_id` per transaction from the *authenticated* principal
-- (set_config(..., true) scopes it to the transaction). A client-supplied tenant id is never used.

create or replace function app_current_tenant() returns uuid
  language sql stable as $$
  select nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

do $$
declare t text;
begin
  foreach t in array array[
    'legal_entity','campus','programme','academic_year','person','identity','role_grant',
    'household','household_member','guardian_relationship','student_enrollment',
    'break_glass_grant','support_access_grant','config_version','audit_event',
    'outbox_event','idempotency_record']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

alter table tenant enable row level security;
alter table tenant force row level security;
create policy tenant_isolation on tenant using (id = app_current_tenant());

-- Least privilege for the application role.
grant usage on schema public to schoomano_app;
grant select on tenant to schoomano_app;
grant select, insert, update, delete on
  legal_entity, campus, programme, academic_year, person, identity, role_grant,
  household, household_member, guardian_relationship, student_enrollment,
  outbox_event, idempotency_record to schoomano_app;
grant select, insert, update on break_glass_grant, support_access_grant to schoomano_app;
grant select, insert, update on config_version to schoomano_app;   -- no delete: history is kept
grant select, insert on audit_event to schoomano_app;               -- append-only
grant execute on function app_current_tenant() to schoomano_app;

-- Login happens before a tenant is known. This narrow SECURITY DEFINER function is the only
-- path that reads identities across tenants, and it returns only what the login flow needs.
create or replace function auth_resolve_identity(p_issuer text, p_subject text)
  returns table (identity_id uuid, person_id uuid, tenant_id uuid, tenant_slug text, tenant_name text)
  language sql stable security definer set search_path = public as $$
  select i.id, i.person_id, i.tenant_id, t.slug, t.display_name
    from identity i join tenant t on t.id = i.tenant_id
   where i.issuer = p_issuer and i.subject = p_subject
     and i.deactivated_at is null and t.status = 'ACTIVE'
$$;
revoke all on function auth_resolve_identity(text, text) from public;
grant execute on function auth_resolve_identity(text, text) to schoomano_app;

-- Publish a draft config version. Closes the currently open version at the new effective_from.
-- Backdating before an already-published version is refused so historical calculations stay stable.
create or replace function config_publish(p_id uuid, p_actor uuid) returns void
  language plpgsql as $$
declare
  d config_version%rowtype;
  latest_from timestamptz;
begin
  select * into d from config_version where id = p_id for update;
  if not found then
    raise exception 'config version % not found', p_id using errcode = 'no_data_found';
  end if;
  if d.status <> 'DRAFT' then
    raise exception 'only DRAFT versions can be published (status=%)', d.status using errcode = 'check_violation';
  end if;

  select max(effective_from) into latest_from from config_version
   where tenant_id = d.tenant_id and config_key = d.config_key and scope_level = d.scope_level
     and scope_id = d.scope_id and status = 'PUBLISHED';
  if latest_from is not null and d.effective_from <= latest_from then
    raise exception 'effective_from % must be after the latest published version (%)', d.effective_from, latest_from
      using errcode = 'check_violation';
  end if;

  update config_version set effective_to = d.effective_from
   where tenant_id = d.tenant_id and config_key = d.config_key and scope_level = d.scope_level
     and scope_id = d.scope_id and status = 'PUBLISHED' and effective_to is null;

  update config_version set status = 'PUBLISHED', published_by = p_actor, published_at = now()
   where id = p_id;
end $$;
grant execute on function config_publish(uuid, uuid) to schoomano_app;

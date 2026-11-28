-- B46 / M30: alerting. When one of the school's operational checks (delivery backlog, failed texts, payments, attendance conflicts,
-- emergency-access reviews) turns from fine to needing attention, the school's administrators are told in their inbox, and
-- reminded once a day while it stays that way. This table remembers each check's last state so nobody is told twice, and
-- so that two instances running at once send one notice between them. A new OPERATIONS kind of staff notice carries it.

alter table notice drop constraint notice_kind_check;
alter table notice add constraint notice_kind_check check (kind in ('ANNOUNCEMENT','ATTENDANCE','SUBSTITUTION','APPROVAL','VISITOR','SECURITY','OPERATIONS'));

create or replace function notice_tell_staff(p_kind text, p_campus uuid, p_title text, p_body text, p_recipients uuid[], p_created_by uuid)
  returns integer language plpgsql security definer set search_path = public as $$
declare n uuid; r uuid; total integer := 0; t uuid := app_current_tenant(); people uuid[];
begin
  if t is null then raise exception 'no school selected' using errcode = 'check_violation'; end if;
  if p_kind not in ('APPROVAL','VISITOR','SECURITY','OPERATIONS') then raise exception 'not a staff notice kind' using errcode = 'check_violation'; end if;
  if p_recipients is null or cardinality(p_recipients) = 0
     or not exists (select 1 from campus where tenant_id = t and id = p_campus) then
    return 0;
  end if;
  select array_agg(id) into people from person where tenant_id = t and status = 'ACTIVE' and id = any (p_recipients);
  if people is null then return 0; end if;
  insert into notice (tenant_id, campus_id, kind, title, body, sms_text, created_by)
    values (t, p_campus, p_kind, left(p_title, 120), left(p_body, 2000), left(p_title, 160), p_created_by) returning id into n;
  foreach r in array people loop
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, channel, status, sent_at)
      values (t, n, r, 'IN_APP', 'SENT', now()) on conflict do nothing;
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, channel, status, skipped_reason)
      values (t, n, r, 'SMS', 'SKIPPED', 'staff notices are in-app only') on conflict do nothing;
    total := total + 1;
  end loop;
  return total;
end $$;

create table ops_alert_state (
  tenant_id   uuid not null references tenant(id),
  check_key   text not null check (check_key ~ '^[a-z]{2,30}$'),
  level       text not null check (level in ('OK', 'ATTENTION')),
  since       timestamptz not null default now(),
  notified_at timestamptz,
  primary key (tenant_id, check_key)
);
alter table ops_alert_state enable row level security;
alter table ops_alert_state force row level security;
create policy tenant_isolation on ops_alert_state using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
grant select, insert, update on ops_alert_state to schoomano_app;

-- Every school that is still live, for the background job that walks them one at a time.
create function ops_alert_tenants() returns setof uuid
  language sql security definer set search_path = public as $$ select id from tenant where status = 'ACTIVE' order by created_at $$;
revoke all on function ops_alert_tenants() from public;
grant execute on function ops_alert_tenants() to schoomano_app;

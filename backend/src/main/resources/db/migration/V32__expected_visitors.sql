-- B32 / M21: pre-registered visitors. A staff member announces a guest for a day; the gate checks the guest in against that
-- announcement, which creates the visit and closes the announcement. Announcements are never edited, only arrived or cancelled.

create table expected_visitor (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  campus_id      uuid not null,
  host_person_id uuid not null,
  visitor_name   text not null check (char_length(btrim(visitor_name)) between 2 and 120),
  organisation   text check (organisation is null or char_length(organisation) <= 120),
  purpose        text not null check (char_length(btrim(purpose)) between 2 and 200),
  expected_on    date not null,
  status         text not null default 'EXPECTED' check (status in ('EXPECTED','ARRIVED','CANCELLED')),
  visit_id       uuid,
  created_at     timestamptz not null default clock_timestamp(),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, host_person_id) references person(tenant_id, id),
  foreign key (tenant_id, visit_id) references visitor_visit(tenant_id, id),
  check ((status = 'ARRIVED') = (visit_id is not null))
);
create index expected_visitor_by_day on expected_visitor (tenant_id, campus_id, expected_on, status);
create unique index expected_visitor_one_visit on expected_visitor (visit_id) where visit_id is not null;

create function expected_visitor_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then raise exception 'announcements are permanent' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' then
    if new.status <> 'EXPECTED' then raise exception 'an announcement starts as expected' using errcode = 'check_violation'; end if;
    return new;
  end if;
  if (new.campus_id, new.host_person_id, new.visitor_name, new.organisation, new.purpose, new.expected_on)
     is distinct from (old.campus_id, old.host_person_id, old.visitor_name, old.organisation, old.purpose, old.expected_on) then
    raise exception 'an announcement cannot be edited' using errcode = 'check_violation';
  end if;
  if new.status = old.status then return new; end if;
  if old.status <> 'EXPECTED' then
    raise exception 'this announcement is already closed' using errcode = 'check_violation';
  end if;
  if new.status = 'ARRIVED' and not exists (
       select 1 from visitor_visit v where v.tenant_id = new.tenant_id and v.id = new.visit_id and v.campus_id = new.campus_id
          and v.host_person_id = new.host_person_id and v.visitor_name = new.visitor_name) then
    raise exception 'arrival needs the matching visit' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger expected_visitor_check before insert or update or delete on expected_visitor
  for each row execute function expected_visitor_guard();

alter table expected_visitor enable row level security;
alter table expected_visitor force row level security;
create policy tenant_isolation on expected_visitor using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());

grant select, insert, update (status, visit_id) on expected_visitor to schoomano_app;

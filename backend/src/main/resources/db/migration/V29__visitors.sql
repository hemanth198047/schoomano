-- B28 / M21 facilities: the campus visitor log. A visit is recorded at the gate and closed once at check-out. Entries are
-- never edited or deleted. A badge number can be on only one person inside at a time.

create table visitor_visit (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  campus_id      uuid not null,
  visitor_name   text not null check (char_length(btrim(visitor_name)) between 2 and 120),
  organisation   text check (organisation is null or char_length(organisation) <= 120),
  phone          text check (phone is null or phone ~ '^[0-9+ -]{6,20}$'),
  purpose        text not null check (char_length(btrim(purpose)) between 2 and 200),
  host_person_id uuid,
  badge_no       text check (badge_no is null or badge_no ~ '^[A-Za-z0-9-]{1,12}$'),
  checked_in_at  timestamptz not null default clock_timestamp(),
  checked_in_by  uuid not null,
  checked_out_at timestamptz,
  checked_out_by uuid,
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, host_person_id) references person(tenant_id, id),
  check (checked_out_at is null or checked_out_at >= checked_in_at),
  check ((checked_out_at is null) = (checked_out_by is null))
);
create index visitor_visit_by_campus on visitor_visit (tenant_id, campus_id, checked_in_at desc);
create unique index visitor_badge_in_use on visitor_visit (tenant_id, campus_id, badge_no) where checked_out_at is null and badge_no is not null;

create function visitor_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then raise exception 'visitor log entries are permanent' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' then
    if new.checked_out_at is not null then
      raise exception 'a visit starts checked in' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if (new.campus_id, new.visitor_name, new.organisation, new.phone, new.purpose, new.host_person_id, new.badge_no, new.checked_in_at, new.checked_in_by)
     is distinct from (old.campus_id, old.visitor_name, old.organisation, old.phone, old.purpose, old.host_person_id, old.badge_no, old.checked_in_at, old.checked_in_by) then
    raise exception 'a visitor log entry cannot be edited' using errcode = 'check_violation';
  end if;
  if old.checked_out_at is not null then
    raise exception 'this visitor has already checked out' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger visitor_visit_check before insert or update or delete on visitor_visit
  for each row execute function visitor_guard();

alter table visitor_visit enable row level security;
alter table visitor_visit force row level security;
create policy tenant_isolation on visitor_visit using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());

grant select, insert, update (checked_out_at, checked_out_by) on visitor_visit to schoomano_app;

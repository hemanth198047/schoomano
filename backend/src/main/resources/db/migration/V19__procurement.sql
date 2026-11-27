-- B17 / M15 finance and procurement. Budgets per campus, year and category; purchase requests that must be approved by
-- someone other than the requester within the budget; vendor payments recorded against approved requests. Money is whole paise.

create table vendor (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references tenant(id),
  campus_id  uuid not null,
  name       text not null check (char_length(btrim(name)) between 2 and 120),
  contact    text check (contact is null or char_length(contact) <= 200),
  active     boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, campus_id, name),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

create table budget (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  campus_id        uuid not null,
  academic_year_id uuid not null,
  category         text not null check (char_length(btrim(category)) between 2 and 60),
  amount_paise     bigint not null check (amount_paise > 0),
  updated_at       timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, campus_id, academic_year_id, category),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

create table purchase_request (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  campus_id        uuid not null,
  academic_year_id uuid not null,
  budget_id        uuid not null,
  vendor_id        uuid,
  title            text not null check (char_length(btrim(title)) between 3 and 200),
  description      text check (description is null or char_length(description) <= 2000),
  amount_paise     bigint not null check (amount_paise > 0),
  requested_by     uuid not null,
  requested_at     timestamptz not null default now(),
  status           text not null default 'SUBMITTED' check (status in ('SUBMITTED','APPROVED','REJECTED','CANCELLED','PAID')),
  decided_by       uuid,
  updated_at       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, budget_id) references budget(tenant_id, id),
  foreign key (tenant_id, vendor_id) references vendor(tenant_id, id)
);
create index purchase_request_by_year on purchase_request (tenant_id, academic_year_id, status);

create table purchase_event (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  request_id    uuid not null,
  from_status   text,
  to_status     text not null,
  note          text check (note is null or char_length(note) <= 2000),
  actor_person_id uuid not null,
  at            timestamptz not null default now(),
  foreign key (tenant_id, request_id) references purchase_request(tenant_id, id)
);

create table vendor_payment (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  request_id    uuid not null,
  amount_paise  bigint not null check (amount_paise > 0),
  paid_on       date not null,
  reference     text not null check (char_length(btrim(reference)) between 2 and 120),
  paid_by       uuid not null,
  unique (tenant_id, request_id),
  foreign key (tenant_id, request_id) references purchase_request(tenant_id, id)
);

-- A request may be edited only by moving along: SUBMITTED to APPROVED, REJECTED or CANCELLED; APPROVED to PAID.
-- Approving needs someone other than the requester and must fit what is left in the budget (budget row locked).
create function purchase_guard() returns trigger language plpgsql as $$
declare b budget%rowtype; committed bigint;
begin
  if tg_op = 'DELETE' then raise exception 'purchase requests are permanent' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' then
    select * into b from budget where tenant_id = new.tenant_id and id = new.budget_id;
    if b.campus_id <> new.campus_id or b.academic_year_id <> new.academic_year_id then
      raise exception 'the budget belongs to a different campus or year' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if (new.amount_paise, new.budget_id, new.vendor_id, new.title, new.requested_by, new.campus_id)
     is distinct from (old.amount_paise, old.budget_id, old.vendor_id, old.title, old.requested_by, old.campus_id) then
    raise exception 'a purchase request cannot be edited after it is raised' using errcode = 'check_violation';
  end if;
  if new.status = old.status then return new; end if;
  if not ((old.status = 'SUBMITTED' and new.status in ('APPROVED','REJECTED','CANCELLED')) or (old.status = 'APPROVED' and new.status = 'PAID')) then
    raise exception 'cannot move a request from % to %', old.status, new.status using errcode = 'check_violation';
  end if;
  if new.status = 'APPROVED' then
    if new.decided_by is null or new.decided_by = new.requested_by then
      raise exception 'a request must be approved by someone other than the requester' using errcode = 'check_violation';
    end if;
    select * into b from budget where tenant_id = new.tenant_id and id = new.budget_id for update;
    select coalesce(sum(amount_paise), 0) into committed from purchase_request
     where tenant_id = new.tenant_id and budget_id = new.budget_id and status in ('APPROVED','PAID') and id <> new.id;
    if committed + new.amount_paise > b.amount_paise then
      raise exception 'over budget' using errcode = 'check_violation';
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger purchase_request_check before insert or update or delete on purchase_request
  for each row execute function purchase_guard();

create function procurement_append_only() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then raise exception '% rows are permanent', tg_table_name using errcode = 'check_violation'; end if;
  if tg_table_name = 'vendor_payment' then
    if not exists (select 1 from purchase_request r where r.tenant_id = new.tenant_id and r.id = new.request_id
                      and r.status = 'APPROVED' and new.amount_paise <= r.amount_paise) then
      raise exception 'a payment needs an approved request and cannot exceed its amount' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end $$;
create trigger purchase_event_permanent before insert or update or delete on purchase_event
  for each row execute function procurement_append_only();
create trigger vendor_payment_permanent before insert or update or delete on vendor_payment
  for each row execute function procurement_append_only();

do $$
declare t text;
begin
  foreach t in array array['vendor','budget','purchase_request','purchase_event','vendor_payment']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on vendor, budget to schoomano_app;
grant select, insert, update (status, decided_by, updated_at) on purchase_request to schoomano_app;
grant select, insert on purchase_event, vendor_payment to schoomano_app;

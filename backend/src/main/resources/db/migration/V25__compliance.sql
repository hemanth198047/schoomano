-- B23 / M24 compliance register. A campus keeps a list of obligations (fire safety certificate, privacy review, statutory
-- return and so on) each with an owner and a next due date. Evidence is recorded against an item and never edited; recording
-- it moves the next due date forward (to the evidence's valid-until date, or by the item's repeat interval; a one-off item with neither is done and has no due date). This is a
-- register, not legal content: what the obligations are is for the school and its advisers to say.

create table compliance_item (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  campus_id       uuid not null,
  title           text not null check (char_length(btrim(title)) between 3 and 160),
  category        text not null check (category in ('FIRE_SAFETY','HEALTH_SAFETY','PRIVACY','SAFEGUARDING','FINANCE','STATUTORY','OTHER')),
  owner_name      text not null check (char_length(btrim(owner_name)) between 2 and 80),
  repeat_months   int check (repeat_months is null or repeat_months between 1 and 60),
  next_due_on     date,
  status          text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  created_by      uuid not null,
  unique (tenant_id, id),
  unique (tenant_id, campus_id, title),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, created_by) references person(tenant_id, id)
);

create table compliance_evidence (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  item_id     uuid not null,
  done_on     date not null,
  reference   text check (reference is null or char_length(btrim(reference)) between 2 and 200),
  note        text check (note is null or char_length(note) <= 1000),
  valid_until date,
  recorded_by uuid not null,
  recorded_at timestamptz not null default clock_timestamp(),
  foreign key (tenant_id, item_id) references compliance_item(tenant_id, id),
  foreign key (tenant_id, recorded_by) references person(tenant_id, id),
  check (valid_until is null or valid_until >= done_on)
);
create index compliance_evidence_by_item on compliance_evidence (tenant_id, item_id, done_on desc);

create function compliance_guard() returns trigger language plpgsql as $$
declare st text;
begin
  if tg_table_name = 'compliance_item' then
    if tg_op = 'DELETE' then raise exception 'compliance items are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.campus_id, new.title, new.category, new.owner_name, new.repeat_months, new.created_by)
         is distinct from (old.campus_id, old.title, old.category, old.owner_name, old.repeat_months, old.created_by) then
        raise exception 'an item cannot be edited, retire it and add a new one' using errcode = 'check_violation'; end if;
      if old.status = 'RETIRED' and new.status <> 'RETIRED' then raise exception 'a retired item stays retired' using errcode = 'check_violation'; end if;
      if old.status = 'RETIRED' and new.next_due_on is distinct from old.next_due_on then raise exception 'a retired item stays as it is' using errcode = 'check_violation'; end if;
      if old.next_due_on is not null and new.next_due_on is not null and new.next_due_on < old.next_due_on then
        raise exception 'the due date only moves forward' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  if tg_op <> 'INSERT' then raise exception 'evidence is permanent' using errcode = 'check_violation'; end if;
  select status into st from compliance_item where tenant_id = new.tenant_id and id = new.item_id;
  if st <> 'ACTIVE' then raise exception 'the item is retired' using errcode = 'check_violation'; end if;
  if new.done_on > (now() at time zone 'Asia/Kolkata')::date then raise exception 'evidence cannot be dated in the future' using errcode = 'check_violation'; end if;
  return new;
end $$;
create trigger compliance_item_check before insert or update or delete on compliance_item for each row execute function compliance_guard();
create trigger compliance_evidence_check before insert or update or delete on compliance_evidence for each row execute function compliance_guard();

do $$
declare t text;
begin
  foreach t in array array['compliance_item','compliance_evidence']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, next_due_on) on compliance_item to schoomano_app;
grant select, insert on compliance_evidence to schoomano_app;

-- B25 / M26 workflow. A campus designs approval chains: an ordered list of steps, each naming the role that must approve.
-- A chain is drafted, then published; a published version never changes, a newer version replaces it for new requests only
-- (the config-versioning pattern). A request pins the version it was submitted under and moves one step at a time; each
-- decision is permanent, the requester can never decide their own request, and the decider must hold the step's role.
-- Triggers and automations are not part of this slice.

create table workflow_definition (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  campus_id    uuid not null,
  chain_key    text not null check (chain_key ~ '^[a-z][a-z0-9-]{2,40}$'),
  version      int not null check (version >= 1),
  name         text not null check (char_length(btrim(name)) between 3 and 120),
  status       text not null default 'DRAFT' check (status in ('DRAFT','PUBLISHED','RETIRED')),
  created_by   uuid not null,
  published_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, campus_id, chain_key, version),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, created_by) references person(tenant_id, id),
  check ((status = 'DRAFT') = (published_at is null))
);
create unique index workflow_one_published on workflow_definition (tenant_id, campus_id, chain_key) where status = 'PUBLISHED';

create table workflow_step (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  definition_id uuid not null,
  position      int not null check (position between 1 and 8),
  role_key      text not null check (role_key ~ '^[a-z][a-z0-9_]{2,40}$'),
  label         text not null check (char_length(btrim(label)) between 2 and 80),
  unique (tenant_id, definition_id, position),
  unique (tenant_id, id),
  foreign key (tenant_id, definition_id) references workflow_definition(tenant_id, id)
);

create table workflow_request (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  campus_id     uuid not null,
  definition_id uuid not null,
  requester_id  uuid not null,
  subject       text not null check (char_length(btrim(subject)) between 3 and 160),
  details       text check (details is null or char_length(details) <= 2000),
  status        text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  current_step  int not null default 1 check (current_step between 1 and 8),
  submitted_at  timestamptz not null default clock_timestamp(),
  closed_at     timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, definition_id) references workflow_definition(tenant_id, id),
  foreign key (tenant_id, requester_id) references person(tenant_id, id),
  check ((status = 'PENDING') = (closed_at is null))
);

create table workflow_decision (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  request_id  uuid not null,
  position    int not null,
  decider_id  uuid not null,
  decision    text not null check (decision in ('APPROVED','REJECTED')),
  note        text check (note is null or char_length(note) <= 1000),
  decided_at  timestamptz not null default clock_timestamp(),
  unique (tenant_id, request_id, position),
  foreign key (tenant_id, request_id) references workflow_request(tenant_id, id),
  foreign key (tenant_id, decider_id) references person(tenant_id, id),
  check (decision = 'APPROVED' or note is not null)
);

create function workflow_guard() returns trigger language plpgsql as $$
declare d record; steps int; approved int; r record; want text;
begin
  if tg_table_name = 'workflow_definition' then
    if tg_op = 'DELETE' then raise exception 'chains are kept' using errcode = 'check_violation'; end if;
    if tg_op = 'UPDATE' then
      if (new.campus_id, new.chain_key, new.version, new.name, new.created_by) is distinct from (old.campus_id, old.chain_key, old.version, old.name, old.created_by) then
        raise exception 'a chain cannot be edited, draft a new version' using errcode = 'check_violation'; end if;
      if (old.status, new.status) not in (('DRAFT','DRAFT'),('DRAFT','PUBLISHED'),('PUBLISHED','PUBLISHED'),('PUBLISHED','RETIRED'),('RETIRED','RETIRED')) then
        raise exception 'a chain moves from draft to published to retired' using errcode = 'check_violation'; end if;
      if new.status = 'PUBLISHED' and old.status = 'DRAFT' and (select count(*) from workflow_step s where s.tenant_id = new.tenant_id and s.definition_id = new.id) = 0 then
        raise exception 'a chain needs at least one step' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  if tg_table_name = 'workflow_step' then
    if tg_op <> 'INSERT' then raise exception 'steps are not edited' using errcode = 'check_violation'; end if;
    select status into want from workflow_definition where tenant_id = new.tenant_id and id = new.definition_id;
    if want <> 'DRAFT' then raise exception 'only a draft chain takes steps' using errcode = 'check_violation'; end if;
    if new.position > 1 and not exists (select 1 from workflow_step s where s.tenant_id = new.tenant_id and s.definition_id = new.definition_id and s.position = new.position - 1) then
      raise exception 'steps are added in order' using errcode = 'check_violation'; end if;
    return new;
  end if;
  if tg_table_name = 'workflow_request' then
    if tg_op = 'DELETE' then raise exception 'requests are kept' using errcode = 'check_violation'; end if;
    select * into d from workflow_definition where tenant_id = new.tenant_id and id = new.definition_id;
    select count(*) into steps from workflow_step s where s.tenant_id = new.tenant_id and s.definition_id = new.definition_id;
    if tg_op = 'INSERT' then
      if d.status <> 'PUBLISHED' then raise exception 'requests use a published chain' using errcode = 'check_violation'; end if;
      if d.campus_id <> new.campus_id then raise exception 'the chain belongs to another campus' using errcode = 'check_violation'; end if;
      if new.status <> 'PENDING' or new.current_step <> 1 then raise exception 'a request starts pending at step 1' using errcode = 'check_violation'; end if;
      return new;
    end if;
    if (new.campus_id, new.definition_id, new.requester_id, new.subject, new.details, new.submitted_at) is distinct from
       (old.campus_id, old.definition_id, old.requester_id, old.subject, old.details, old.submitted_at) then
      raise exception 'a request cannot be edited' using errcode = 'check_violation'; end if;
    if old.status <> 'PENDING' then raise exception 'a closed request stays closed' using errcode = 'check_violation'; end if;
    if new.status = 'PENDING' then
      if new.current_step <> old.current_step + 1 or new.current_step > steps then raise exception 'a request moves one step at a time' using errcode = 'check_violation'; end if;
      if not exists (select 1 from workflow_decision x where x.tenant_id = new.tenant_id and x.request_id = new.id and x.position = old.current_step and x.decision = 'APPROVED') then
        raise exception 'the step was not approved' using errcode = 'check_violation'; end if;
    elsif new.status = 'APPROVED' then
      select count(*) into approved from workflow_decision x where x.tenant_id = new.tenant_id and x.request_id = new.id and x.decision = 'APPROVED';
      if approved <> steps then raise exception 'every step must approve first' using errcode = 'check_violation'; end if;
    elsif new.status = 'REJECTED' then
      if not exists (select 1 from workflow_decision x where x.tenant_id = new.tenant_id and x.request_id = new.id and x.decision = 'REJECTED') then
        raise exception 'no step rejected it' using errcode = 'check_violation'; end if;
    end if;
    return new;
  end if;
  -- workflow_decision
  if tg_op <> 'INSERT' then raise exception 'decisions are permanent' using errcode = 'check_violation'; end if;
  select * into r from workflow_request where tenant_id = new.tenant_id and id = new.request_id;
  if r.status <> 'PENDING' then raise exception 'the request is closed' using errcode = 'check_violation'; end if;
  if new.position <> r.current_step then raise exception 'it is not that step''s turn' using errcode = 'check_violation'; end if;
  if new.decider_id = r.requester_id then raise exception 'nobody decides their own request' using errcode = 'check_violation'; end if;
  select s.role_key into want from workflow_step s where s.tenant_id = new.tenant_id and s.definition_id = r.definition_id and s.position = new.position;
  if not exists (select 1 from role_grant g where g.tenant_id = new.tenant_id and g.person_id = new.decider_id and g.role_key = want
                    and (g.valid_to is null or g.valid_to > now())) then
    raise exception 'the decider does not hold the role for this step' using errcode = 'check_violation'; end if;
  return new;
end $$;
create trigger workflow_definition_check before insert or update or delete on workflow_definition for each row execute function workflow_guard();
create trigger workflow_step_check before insert or update or delete on workflow_step for each row execute function workflow_guard();
create trigger workflow_request_check before insert or update or delete on workflow_request for each row execute function workflow_guard();
create trigger workflow_decision_check before insert or update or delete on workflow_decision for each row execute function workflow_guard();

do $$
declare t text;
begin
  foreach t in array array['workflow_definition','workflow_step','workflow_request','workflow_decision']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status, published_at) on workflow_definition to schoomano_app;
grant select, insert on workflow_step, workflow_decision to schoomano_app;
grant select, insert, update (status, current_step, closed_at) on workflow_request to schoomano_app;

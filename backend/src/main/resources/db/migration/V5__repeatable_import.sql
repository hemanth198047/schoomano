-- B2 repeatable import (coexistence with the old school system, decision D5).
-- A batch is validated (dry run, nothing applied), then committed or discarded; a committed batch can be
-- rolled back while it is the latest one. external_link remembers what the old system sent last time, so
-- the next import can tell old-system changes from Schoomano edits (three-way comparison).

create table import_batch (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  entity           text not null check (entity in ('STUDENT')),
  source           text not null default 'LEGACY' check (source ~ '^[A-Z_]{2,20}$'),
  academic_year_id uuid not null,
  file_name        text not null check (char_length(file_name) between 1 and 200),
  file_sha256      bytea not null,
  file_table       jsonb not null,             -- parsed rows, kept to re-check the plan at commit time
  status           text not null default 'VALIDATED'
                   check (status in ('VALIDATED','COMMITTED','ROLLED_BACK','DISCARDED')),
  counts           jsonb not null default '{}'::jsonb,
  file_problems    jsonb not null default '[]'::jsonb,
  missing_refs     jsonb not null default '[]'::jsonb,
  retention_class  text not null default 'TRANSIENT',
  created_by       uuid not null,
  created_at       timestamptz not null default now(),
  committed_by     uuid,
  committed_at     timestamptz,
  rolled_back_by   uuid,
  rolled_back_at   timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);
create index import_batch_recent_idx on import_batch (tenant_id, entity, created_at desc);

create table import_row (
  tenant_id    uuid not null references tenant(id),
  batch_id     uuid not null,
  row_no       integer not null,
  external_ref text not null,
  action       text not null check (action in ('CREATE','UPDATE','UNCHANGED','CONFLICT','ERROR')),
  problems     jsonb not null default '[]'::jsonb,
  changes      jsonb not null default '[]'::jsonb,
  incoming     jsonb not null default '{}'::jsonb,
  person_id    uuid,
  applied      boolean not null default false,
  before_values jsonb,                         -- Schoomano values just before commit (for rollback)
  link_before  jsonb,                          -- external_link.last_values just before commit (for rollback)
  primary key (tenant_id, batch_id, row_no),
  foreign key (tenant_id, batch_id) references import_batch(tenant_id, id)
);

create table external_link (
  tenant_id     uuid not null references tenant(id),
  source        text not null,
  entity        text not null,
  external_ref  text not null,                 -- upper-cased admission number
  person_id     uuid not null,
  last_values   jsonb not null,
  last_year_id  uuid,
  last_batch_id uuid,
  updated_at    timestamptz not null default now(),
  primary key (tenant_id, source, entity, external_ref),
  unique (tenant_id, source, entity, person_id),
  foreign key (tenant_id, person_id) references person(tenant_id, id)
);
-- "Is this person linked?" lookups (by person, any source). Keeps import planning fast even right after a
-- large first import, before PostgreSQL has statistics for the new rows.
create index external_link_person_idx on external_link (tenant_id, person_id);

do $$
declare t text;
begin
  foreach t in array array['import_batch','import_row','external_link']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update on import_batch, import_row to schoomano_app;
grant select, insert, update, delete on external_link to schoomano_app;

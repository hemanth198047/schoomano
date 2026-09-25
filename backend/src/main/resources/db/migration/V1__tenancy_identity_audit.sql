-- Schoomano baseline: tenancy hierarchy, identity, authorization grants,
-- guardian relationships, effective-dated configuration, audit, outbox, idempotency.
-- Every tenant-owned table carries tenant_id and is protected by RLS in V2.

create extension if not exists btree_gist;

-- The application login role. Infra (docker init / managed DB provisioning) normally
-- creates it WITH LOGIN; this guard keeps migrations runnable on a bare database.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'schoomano_app') then
    create role schoomano_app nologin;
  end if;
end $$;

-- Shared vocabularies kept as CHECK constraints so they are visible in the schema.
-- scope_level: GROUP (= tenant), LEGAL_ENTITY, CAMPUS, PROGRAMME, ACADEMIC_YEAR, CLASS
-- retention_class: STANDARD, FINANCIAL, SAFEGUARDING, HEALTH, AUDIT, TRANSIENT

create table tenant (
  id            uuid primary key default gen_random_uuid(),
  slug          text not null unique check (slug ~ '^[a-z0-9-]{3,40}$'),
  display_name  text not null,
  status        text not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED','OFFBOARDING')),
  data_region   text not null default 'IN',
  created_at    timestamptz not null default now()
);

create table legal_entity (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenant(id),
  name          text not null,
  registration_ref text,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id)
);

create table campus (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  legal_entity_id uuid not null,
  code            text not null check (code ~ '^[A-Z0-9-]{2,16}$'),
  name            text not null,
  time_zone       text not null default 'Asia/Kolkata',
  status          text not null default 'ACTIVE' check (status in ('SETUP','ACTIVE','ARCHIVED')),
  created_at      timestamptz not null default now(),
  unique (tenant_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, legal_entity_id) references legal_entity(tenant_id, id)
);

create table programme (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  campus_id    uuid not null,
  code         text not null,
  name         text not null,
  board        text not null check (board in ('CBSE','CISCE','IB','CAMBRIDGE','STATE','OTHER')),
  board_detail text,
  created_at   timestamptz not null default now(),
  unique (tenant_id, campus_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

create table academic_year (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references tenant(id),
  campus_id  uuid not null,
  label      text not null,
  starts_on  date not null,
  ends_on    date not null,
  status     text not null default 'PLANNED' check (status in ('PLANNED','ACTIVE','CLOSED')),
  created_at timestamptz not null default now(),
  check (ends_on > starts_on),
  unique (tenant_id, campus_id, label),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  -- Two academic years of one campus may never overlap; rollover creates a new year.
  exclude using gist (campus_id with =, daterange(starts_on, ends_on, '[]') with &&)
);

-- A person is stable across years and roles: the same person may be guardian, teacher and employee.
create table person (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  school_ref      text not null,            -- human-facing stable ID, e.g. SCH-000123
  given_name      text not null,
  family_name     text,
  preferred_name  text,
  date_of_birth   date,
  status          text not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE','MERGED')),
  merged_into     uuid,
  retention_class text not null default 'STANDARD'
                  check (retention_class in ('STANDARD','FINANCIAL','SAFEGUARDING','HEALTH','AUDIT','TRANSIENT')),
  provenance      jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  updated_at      timestamptz not null default now(),
  updated_by      uuid,
  unique (tenant_id, school_ref),
  unique (tenant_id, id)
);

-- Login identities. One human with children in two unrelated school groups has two
-- identities (one per tenant) under the same issuer/subject only if the IdP issues distinct subjects;
-- otherwise the login flow asks which tenant to enter.
create table identity (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  person_id      uuid not null,
  issuer         text not null,
  subject        text not null,
  email          text,
  phone_e164     text check (phone_e164 is null or phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
  verified_at    timestamptz,
  deactivated_at timestamptz,
  created_at     timestamptz not null default now(),
  unique (tenant_id, issuer, subject),
  foreign key (tenant_id, person_id) references person(tenant_id, id)
);

create table role_grant (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  person_id   uuid not null,
  role_key    text not null check (role_key ~ '^[a-z][a-z0-9_]{2,40}$'),
  scope_level text not null check (scope_level in ('GROUP','LEGAL_ENTITY','CAMPUS','PROGRAMME','ACADEMIC_YEAR','CLASS')),
  scope_id    uuid not null,
  valid_from  timestamptz not null default now(),
  valid_to    timestamptz,
  granted_by  uuid,
  reason      text,
  created_at  timestamptz not null default now(),
  check (valid_to is null or valid_to > valid_from),
  foreign key (tenant_id, person_id) references person(tenant_id, id)
);
create index role_grant_person_idx on role_grant (tenant_id, person_id);

create table household (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references tenant(id),
  name       text not null,
  provenance jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique (tenant_id, id)
);

create table household_member (
  tenant_id    uuid not null references tenant(id),
  household_id uuid not null,
  person_id    uuid not null,
  member_role  text not null check (member_role in ('GUARDIAN','STUDENT','OTHER')),
  primary key (tenant_id, household_id, person_id),
  foreign key (tenant_id, household_id) references household(tenant_id, id),
  foreign key (tenant_id, person_id) references person(tenant_id, id)
);

-- Relationship-aware access to children. Capabilities are explicit per relationship so a
-- separated guardian can receive notices without seeing restricted records or paying fees.
-- Court-order details never live here; restriction evidence belongs in the restricted document vault.
create table guardian_relationship (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references tenant(id),
  guardian_person_id   uuid not null,
  student_person_id    uuid not null,
  relationship_type    text not null check (relationship_type in ('MOTHER','FATHER','GUARDIAN','GRANDPARENT','OTHER')),
  can_view_attendance  boolean not null default true,
  can_view_academic    boolean not null default true,
  can_pay_fees         boolean not null default true,
  can_give_consent     boolean not null default true,
  can_receive_notices  boolean not null default true,
  can_pickup           boolean not null default false,
  status               text not null default 'ACTIVE' check (status in ('ACTIVE','RESTRICTED','SUSPENDED','ENDED')),
  valid_from           timestamptz not null default now(),
  valid_to             timestamptz,
  retention_class      text not null default 'STANDARD',
  provenance           jsonb not null default '{}'::jsonb,
  created_at           timestamptz not null default now(),
  check (guardian_person_id <> student_person_id),
  foreign key (tenant_id, guardian_person_id) references person(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id)
);
create index guardian_rel_guardian_idx on guardian_relationship (tenant_id, guardian_person_id);
create index guardian_rel_student_idx on guardian_relationship (tenant_id, student_person_id);

-- Student placement per year keeps the student-to-campus mapping historical (M03/M04 build on this).
create table student_enrollment (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  student_person_id uuid not null,
  campus_id        uuid not null,
  academic_year_id uuid not null,
  programme_id     uuid,
  status           text not null default 'ENROLLED' check (status in ('ENROLLED','TRANSFERRED_OUT','WITHDRAWN','COMPLETED')),
  created_at       timestamptz not null default now(),
  unique (tenant_id, student_person_id, academic_year_id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

-- Break-glass access to a restricted record: justification, short expiry, mandatory review.
create table break_glass_grant (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  person_id      uuid not null,
  resource_type  text not null,
  resource_id    uuid not null,
  justification  text not null check (char_length(justification) >= 20),
  granted_at     timestamptz not null default now(),
  expires_at     timestamptz not null,
  reviewed_by    uuid,
  reviewed_at    timestamptz,
  review_outcome text check (review_outcome in ('APPROPRIATE','INAPPROPRIATE','ESCALATED')),
  check (expires_at > granted_at and expires_at <= granted_at + interval '4 hours'),
  foreign key (tenant_id, person_id) references person(tenant_id, id)
);

-- Platform support may enter a tenant only through a tenant-approved, time-limited grant (M30).
create table support_access_grant (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  support_user_ref text not null,
  approved_by      uuid not null,
  purpose          text not null check (char_length(purpose) >= 10),
  starts_at        timestamptz not null default now(),
  expires_at       timestamptz not null,
  revoked_at       timestamptz,
  check (expires_at > starts_at and expires_at <= starts_at + interval '8 hours'),
  foreign key (tenant_id, approved_by) references person(tenant_id, id)
);

-- Effective-dated configuration, including feature flags (keys prefixed 'flag.').
-- Published versions for one key+scope never overlap in time; history is never rewritten.
create table config_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  config_key     text not null check (config_key ~ '^[a-z][a-z0-9_.]{2,80}$'),
  scope_level    text not null check (scope_level in ('GROUP','LEGAL_ENTITY','CAMPUS','PROGRAMME','ACADEMIC_YEAR','CLASS')),
  scope_id       uuid not null,
  version        integer not null check (version > 0),
  value          jsonb not null,
  effective_from timestamptz not null,
  effective_to   timestamptz,
  status         text not null default 'DRAFT' check (status in ('DRAFT','PUBLISHED','RETIRED')),
  change_note    text not null,
  created_by     uuid not null,
  created_at     timestamptz not null default now(),
  published_by   uuid,
  published_at   timestamptz,
  check (effective_to is null or effective_to > effective_from),
  unique (tenant_id, config_key, scope_level, scope_id, version),
  exclude using gist (
    tenant_id with =, config_key with =, scope_level with =, scope_id with =,
    tstzrange(effective_from, effective_to, '[)') with &&
  ) where (status = 'PUBLISHED')
);

-- Append-only, hash-chained audit trail per tenant.
create table audit_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  seq            bigint not null,
  occurred_at    timestamptz not null default now(),
  actor_person_id uuid,
  actor_kind     text not null check (actor_kind in ('USER','SUPPORT','SYSTEM','BREAK_GLASS','INTEGRATION')),
  action         text not null,
  resource_type  text not null,
  resource_id    text,
  scope_level    text,
  scope_id       uuid,
  outcome        text not null check (outcome in ('ALLOW','DENY','ERROR','SUCCESS')),
  reason         text,
  correlation_id text,
  policy_version text,
  details        jsonb not null default '{}'::jsonb,  -- never child health or case narrative
  prev_hash      bytea,
  hash           bytea not null,
  unique (tenant_id, seq)
);
create index audit_event_resource_idx on audit_event (tenant_id, resource_type, resource_id);

create or replace function audit_event_chain() returns trigger language plpgsql as $$
declare
  last_hash bytea;
  last_seq  bigint;
begin
  -- Serialise writers per tenant so the chain has no forks.
  perform pg_advisory_xact_lock(hashtextextended(new.tenant_id::text, 7));
  select seq, hash into last_seq, last_hash
    from audit_event where tenant_id = new.tenant_id order by seq desc limit 1;
  new.seq := coalesce(last_seq, 0) + 1;
  new.prev_hash := last_hash;
  new.hash := sha256(convert_to(concat_ws('|',
      encode(coalesce(last_hash, '\x'::bytea), 'hex'), new.tenant_id, new.seq, new.occurred_at,
      new.actor_person_id, new.actor_kind, new.action, new.resource_type, new.resource_id,
      new.outcome, new.reason, new.correlation_id, new.policy_version, new.details::text), 'UTF8'));
  return new;
end $$;

create trigger audit_event_chain_trg before insert on audit_event
  for each row execute function audit_event_chain();

create or replace function audit_event_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'audit_event is append-only' using errcode = 'insufficient_privilege';
end $$;

create trigger audit_event_no_update before update or delete on audit_event
  for each row execute function audit_event_immutable();

-- Transactional outbox: domain changes and their events commit atomically.
create table outbox_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  aggregate_type text not null,
  aggregate_id   text not null,
  event_type     text not null,
  payload        jsonb not null,
  occurred_at    timestamptz not null default now(),
  published_at   timestamptz,
  attempts       integer not null default 0,
  last_error     text
);
create index outbox_unpublished_idx on outbox_event (occurred_at) where published_at is null;

-- Idempotency for API writes and external callbacks (gateway webhooks, offline sync).
create table idempotency_record (
  tenant_id       uuid not null references tenant(id),
  scope           text not null,
  idem_key        text not null check (char_length(idem_key) between 8 and 200),
  request_hash    bytea not null,
  response_status integer,
  response_body   jsonb,
  created_at      timestamptz not null default now(),
  expires_at      timestamptz not null default now() + interval '7 days',
  primary key (tenant_id, scope, idem_key)
);

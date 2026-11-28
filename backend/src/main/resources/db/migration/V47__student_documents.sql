-- B59 / M03: documents kept with a student (birth certificate, transfer certificate, ID proof, medical letter). The file itself
-- is stored in the database next to the record, so backups, restores and tenant isolation cover it with no second store.
-- Only the school office reads or changes them. Removing a document deletes the file but keeps who removed it, when, and why.
create table student_document (
  tenant_id     uuid not null references tenant (id),
  id            uuid not null default gen_random_uuid(),
  student_id    uuid not null,
  kind          text not null check (kind in ('BIRTH_CERTIFICATE', 'TRANSFER_CERTIFICATE', 'ID_PROOF', 'MEDICAL', 'OTHER')),
  file_name     text not null check (length(file_name) between 1 and 120),
  content_type  text not null check (content_type in ('application/pdf', 'image/png', 'image/jpeg')),
  size_bytes    integer not null check (size_bytes between 1 and 5242880),
  sha256        text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  note          text check (length(note) <= 300),
  uploaded_by   uuid not null,
  uploaded_at   timestamptz not null default now(),
  removed_at    timestamptz,
  removed_by    uuid,
  remove_reason text,
  primary key (tenant_id, id),
  foreign key (tenant_id, student_id) references person (tenant_id, id),
  check ((removed_at is null) = (removed_by is null) and (removed_at is null) = (remove_reason is null))
);
create index student_document_student on student_document (tenant_id, student_id, uploaded_at desc);

create table student_document_content (
  tenant_id   uuid not null,
  document_id uuid not null,
  content     bytea not null,
  primary key (tenant_id, document_id),
  foreign key (tenant_id, document_id) references student_document (tenant_id, id)
);

alter table student_document enable row level security;
alter table student_document force row level security;
create policy tenant_isolation on student_document using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
alter table student_document_content enable row level security;
alter table student_document_content force row level security;
create policy tenant_isolation on student_document_content using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
grant select, insert, update on student_document to schoomano_app;
grant select, insert, delete on student_document_content to schoomano_app;

-- A document row never changes except to record its removal; its file goes away when it is removed (and only then).
create function student_document_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then raise exception 'documents are not deleted, they are removed' using errcode = 'check_violation'; end if;
  if tg_op = 'UPDATE' then
    if old.removed_at is not null then raise exception 'a removed document stays removed' using errcode = 'check_violation'; end if;
    if (new.tenant_id, new.id, new.student_id, new.kind, new.file_name, new.content_type, new.size_bytes, new.sha256, new.note, new.uploaded_by, new.uploaded_at)
       is distinct from (old.tenant_id, old.id, old.student_id, old.kind, old.file_name, old.content_type, old.size_bytes, old.sha256, old.note, old.uploaded_by, old.uploaded_at) then
      raise exception 'only the removal of a document can be recorded' using errcode = 'check_violation';
    end if;
    if new.removed_at is null or length(btrim(new.remove_reason)) < 10 then
      raise exception 'removing a document needs a reason of at least 10 characters' using errcode = 'check_violation';
    end if;
  end if;
  if tg_op = 'INSERT' and (select count(*) from student_document where tenant_id = new.tenant_id and student_id = new.student_id and removed_at is null) >= 20 then
    raise exception 'a student can have at most 20 documents' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger student_document_check before insert or update or delete on student_document for each row execute function student_document_guard();

-- The file may only be added for a live document, and only removed together with its document.
create function student_document_content_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' then raise exception 'a stored file is never changed' using errcode = 'check_violation'; end if;
  if tg_op = 'INSERT' and exists (select 1 from student_document where tenant_id = new.tenant_id and id = new.document_id and removed_at is not null) then
    raise exception 'the document is removed' using errcode = 'check_violation';
  end if;
  if tg_op = 'DELETE' and not exists (select 1 from student_document where tenant_id = old.tenant_id and id = old.document_id and removed_at is not null) then
    raise exception 'a file is deleted only when its document is removed' using errcode = 'check_violation';
  end if;
  return coalesce(new, old);
end $$;
create trigger student_document_content_check before insert or update or delete on student_document_content for each row execute function student_document_content_guard();

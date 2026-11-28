-- B59 student documents. Run as the schema owner after migrations and dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

insert into student_document (tenant_id, id, student_id, kind, file_name, content_type, size_bytes, sha256, uploaded_by) values
  ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'BIRTH_CERTIFICATE',
   'birth.pdf', 'application/pdf', 4, repeat('a', 64), '13000000-0000-4000-8000-000000000001');
insert into student_document_content (tenant_id, document_id, content) values
  ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '\x25504446');

-- D1: nothing about an uploaded document can be edited, only its removal recorded.
do $$ begin
  begin
    update student_document set file_name = 'other.pdf' where id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D1 failed: a document was edited';
  exception when check_violation then null; end;
  raise notice 'D1 pass: an uploaded document cannot be edited';
end $$;

-- D2: a file cannot be deleted while its document is live, nor a document deleted at all.
do $$ begin
  begin
    delete from student_document_content where document_id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D2 failed: a live file was deleted';
  exception when check_violation then null; end;
  begin
    delete from student_document where id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D2 failed: a document row was deleted';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'D2 pass: a live file and a document row cannot be deleted';
end $$;

-- D3: removing needs a reason; a file is never changed.
do $$ begin
  begin
    update student_document set removed_at = now(), removed_by = '13000000-0000-4000-8000-000000000001', remove_reason = 'short'
     where id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D3 failed: removed without a proper reason';
  exception when check_violation then null; end;
  begin
    update student_document_content set content = '\x00' where document_id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D3 failed: a stored file was changed';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'D3 pass: removal needs a reason and files are not changed';
end $$;

-- D4: after removal the file can go, the row stays and cannot be undone, and no file can be added back.
do $$ begin
  update student_document set removed_at = now(), removed_by = '13000000-0000-4000-8000-000000000001', remove_reason = 'Uploaded to the wrong child'
   where id = '1d000000-0000-4000-8000-000000000001';
  delete from student_document_content where document_id = '1d000000-0000-4000-8000-000000000001';
  begin
    update student_document set removed_at = null, removed_by = null, remove_reason = null where id = '1d000000-0000-4000-8000-000000000001';
    raise exception 'D4 failed: a removal was undone';
  exception when check_violation then null; end;
  begin
    insert into student_document_content (tenant_id, document_id, content) values
      ('10000000-0000-4000-8000-000000000001', '1d000000-0000-4000-8000-000000000001', '\x25504446');
    raise exception 'D4 failed: a file was added to a removed document';
  exception when check_violation then null; end;
  raise notice 'D4 pass: removal is final and takes the file with it';
end $$;

-- D5: at most 20 live documents for a student.
do $$ begin
  for i in 1..20 loop
    insert into student_document (tenant_id, student_id, kind, file_name, content_type, size_bytes, sha256, uploaded_by) values
      ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'OTHER', 'f' || i || '.pdf', 'application/pdf', 4, repeat('b', 64), '13000000-0000-4000-8000-000000000001');
  end loop;
  begin
    insert into student_document (tenant_id, student_id, kind, file_name, content_type, size_bytes, sha256, uploaded_by) values
      ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'OTHER', 'f21.pdf', 'application/pdf', 4, repeat('b', 64), '13000000-0000-4000-8000-000000000001');
    raise exception 'D5 failed: a 21st document was accepted';
  exception when check_violation then null; end;
  raise notice 'D5 pass: 20 live documents at most';
end $$;

-- D6: another school sees none of it.
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
do $$ begin
  if (select count(*) from student_document) <> 0 then raise exception 'D6 failed: another school sees documents'; end if;
  raise notice 'D6 pass: documents are private to the school';
end $$;
rollback;

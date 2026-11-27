-- B30 approval notices. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- N1: named active staff get an in-app notice and never an SMS; duplicates collapse; nobody else is reached.
do $$ declare n integer; begin
  n := notice_tell_staff('APPROVAL', '12000000-0000-4000-8000-000000000001', 'Approval waiting', 'Projector is waiting for you.',
    array['13000000-0000-4000-8000-000000000003','13000000-0000-4000-8000-000000000003']::uuid[], '13000000-0000-4000-8000-000000000002');
  if n <> 1 then raise exception 'N1 failed: reached % people', n; end if;
  if (select count(*) from notice_delivery d join notice x on x.id = d.notice_id
       where x.kind = 'APPROVAL' and d.channel = 'IN_APP' and d.recipient_person_id = '13000000-0000-4000-8000-000000000003') <> 1 then
    raise exception 'N1 failed: no in-app delivery'; end if;
  if exists (select 1 from notice_delivery d join notice x on x.id = d.notice_id where x.kind = 'APPROVAL' and d.channel = 'SMS' and d.status <> 'SKIPPED') then
    raise exception 'N1 failed: an SMS was queued'; end if;
  if notice_tell_staff('APPROVAL', '12000000-0000-4000-8000-000000000001', 'Nobody', 'x', array[]::uuid[], null) <> 0 then raise exception 'N1 failed: empty list'; end if;
  if notice_tell_staff('APPROVAL', '12000000-0000-4000-8000-000000000001', 'Stranger', 'x', array['13000000-0000-4000-8000-0000000000ff']::uuid[], null) <> 0 then
    raise exception 'N1 failed: unknown person reached'; end if;
  raise notice 'N1 pass: in-app only, deduplicated, known staff only';
end $$;

-- N1b: only staff notice kinds are allowed, and a visitor notice is filed as one.
do $$ begin
  begin perform notice_tell_staff('ANNOUNCEMENT', '12000000-0000-4000-8000-000000000001', 'Spoof', 'x', array['13000000-0000-4000-8000-000000000003']::uuid[], null);
    raise exception 'N1b failed: announcement kind accepted';
  exception when check_violation then null; end;
  if notice_tell_staff('VISITOR', '12000000-0000-4000-8000-000000000001', 'Visitor arrived', 'x', array['13000000-0000-4000-8000-000000000003']::uuid[], null) <> 1 then
    raise exception 'N1b failed: visitor notice not sent'; end if;
  raise notice 'N1b pass: staff kinds only';
end $$;

-- N2: a school can only notify its own people.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if notice_tell_staff('APPROVAL', '12000000-0000-4000-8000-000000000001', 'Cross', 'x', array['13000000-0000-4000-8000-000000000003']::uuid[], null) <> 0 then
    raise exception 'N2 failed: another school''s person reached'; end if;
  raise notice 'N2 pass: tenants are isolated';
end $$;
rollback;

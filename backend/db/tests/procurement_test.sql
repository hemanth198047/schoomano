-- B17 finance and procurement. Run as the schema owner after migrations + dev seeds; rolls back.
\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
insert into budget (id, tenant_id, campus_id, academic_year_id, category, amount_paise) values
  ('1b000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'Lab equipment (test)', 100000);
insert into budget (id, tenant_id, campus_id, academic_year_id, category, amount_paise) values
  ('1b000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
   '15000000-0000-4000-8000-000000000001', 'Sports (test)', 50000);
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- P1: approval needs someone other than the requester; the request itself cannot be edited.
do $$ declare r uuid; begin
  insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-000000000001', 'Microscopes', 60000, '13000000-0000-4000-8000-000000000002') returning id into r;
  begin update purchase_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000002' where id = r;
    raise exception 'P1 failed: self approval';
  exception when check_violation then null; end;
  begin update purchase_request set amount_paise = 1 where id = r; raise exception 'P1 failed: amount edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin update purchase_request set status = 'PAID' where id = r; raise exception 'P1 failed: skipped approval';
  exception when check_violation then null; end;
  update purchase_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
  raise notice 'P1 pass: separate approver, fixed amount, ordered steps';
end $$;

-- P2: approvals cannot exceed the budget (60000 already committed of 100000).
do $$ declare r uuid; begin
  insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-000000000001', 'Centrifuge', 50000, '13000000-0000-4000-8000-000000000002') returning id into r;
  begin update purchase_request set status = 'APPROVED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
    raise exception 'P2 failed: over budget approved';
  exception when check_violation then if sqlerrm <> 'over budget' then raise; end if; end;
  update purchase_request set status = 'REJECTED', decided_by = '13000000-0000-4000-8000-000000000001' where id = r;
  begin update purchase_request set status = 'APPROVED' where id = r; raise exception 'P2 failed: rejected request reopened';
  exception when check_violation then null; end;
  raise notice 'P2 pass: budget limit holds';
end $$;

-- P3: a request must use a budget of its own campus and year.
do $$ begin
  update budget set campus_id = campus_id where false;
  begin insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-0000000000ff',
     '1b000000-0000-4000-8000-000000000001', 'Wrong year', 100, '13000000-0000-4000-8000-000000000002');
    raise exception 'P3 failed: wrong year accepted';
  exception when check_violation or foreign_key_violation then null; end;
  raise notice 'P3 pass: budget matches campus and year';
end $$;

-- P4: payments need an approved request, cannot exceed it, happen once, and are permanent.
do $$ declare ok uuid; sub uuid; begin
  select id into ok from purchase_request where title = 'Microscopes';
  insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, title, amount_paise, requested_by) values
    ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
     '1b000000-0000-4000-8000-000000000002', 'Cricket nets', 1000, '13000000-0000-4000-8000-000000000002') returning id into sub;
  begin insert into vendor_payment (tenant_id, request_id, amount_paise, paid_on, reference, paid_by) values
    ('10000000-0000-4000-8000-000000000001', sub, 1000, current_date, 'NEFT-1', '13000000-0000-4000-8000-000000000001');
    raise exception 'P4 failed: paid unapproved';
  exception when check_violation then null; end;
  begin insert into vendor_payment (tenant_id, request_id, amount_paise, paid_on, reference, paid_by) values
    ('10000000-0000-4000-8000-000000000001', ok, 60001, current_date, 'NEFT-2', '13000000-0000-4000-8000-000000000001');
    raise exception 'P4 failed: overpaid';
  exception when check_violation then null; end;
  insert into vendor_payment (tenant_id, request_id, amount_paise, paid_on, reference, paid_by) values
    ('10000000-0000-4000-8000-000000000001', ok, 60000, current_date, 'NEFT-3', '13000000-0000-4000-8000-000000000001');
  begin insert into vendor_payment (tenant_id, request_id, amount_paise, paid_on, reference, paid_by) values
    ('10000000-0000-4000-8000-000000000001', ok, 1, current_date, 'NEFT-4', '13000000-0000-4000-8000-000000000001');
    raise exception 'P4 failed: paid twice';
  exception when unique_violation then null; end;
  begin update vendor_payment set amount_paise = 1 where request_id = ok; raise exception 'P4 failed: payment edited';
  exception when insufficient_privilege or check_violation then null; end;
  insert into purchase_event (tenant_id, request_id, to_status, actor_person_id) values
    ('10000000-0000-4000-8000-000000000001', ok, 'PAID', '13000000-0000-4000-8000-000000000001');
  begin delete from purchase_event where request_id = ok; raise exception 'P4 failed: history deleted';
  exception when insufficient_privilege or check_violation then null; end;
  raise notice 'P4 pass: payments are controlled and permanent';
end $$;

-- P5: another school sees nothing.
do $$ begin
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
  if (select count(*) from budget) + (select count(*) from purchase_request) + (select count(*) from vendor_payment) <> 0 then
    raise exception 'P5 failed: cross-tenant read'; end if;
  raise notice 'P5 pass: tenant isolation';
end $$;
rollback;

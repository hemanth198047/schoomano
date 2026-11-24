-- B8 fees invariants. Run after migrations + dev seeds as the schema owner; rolls back.
-- Fixture: Anaya (…20), North 2026-27. Asha (…01) issues. Money is in paise.

\set ON_ERROR_STOP on
set client_min_messages = notice;
begin;
set local role schoomano_app;
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

create function pg_temp.inv(total bigint, lines bigint[]) returns uuid language plpgsql as $$
declare v_id uuid; l bigint; n int := 0;
begin
  insert into invoice (tenant_id, campus_id, academic_year_id, student_person_id, number, due_on, total_paise, issued_by)
  values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
          '13000000-0000-4000-8000-000000000020', fee_next_invoice_number('15000000-0000-4000-8000-000000000001'),
          '2026-12-31', total, '13000000-0000-4000-8000-000000000001') returning id into v_id;
  foreach l in array lines loop
    n := n + 1;
    insert into invoice_line (tenant_id, invoice_id, description, amount_paise)
    values ('10000000-0000-4000-8000-000000000001', v_id, 'Line ' || n, l);
  end loop;
  return v_id;
end $$;
create function pg_temp.cash(inv uuid, amt bigint, key text default null) returns uuid language sql as $$
  insert into payment (tenant_id, invoice_id, amount_paise, method, status, idempotency_key, created_by, completed_at)
  values ('10000000-0000-4000-8000-000000000001', inv, amt, 'CASH', 'SUCCEEDED', key, '13000000-0000-4000-8000-000000000001', now()) returning id $$;
create function pg_temp.online(inv uuid, amt bigint, ord text) returns uuid language sql as $$
  insert into payment (tenant_id, invoice_id, amount_paise, method, status, gateway_order_id, created_by)
  values ('10000000-0000-4000-8000-000000000001', inv, amt, 'GATEWAY', 'PENDING', ord, '13000000-0000-4000-8000-000000000010') returning id $$;
create function pg_temp.st(inv uuid) returns text language sql as $$
  select status || ':' || paid_paise from invoice where id = inv $$;

-- FT1: isolation.
do $$ declare i uuid; begin
  i := pg_temp.inv(500000, array[300000, 200000]);
  perform set_config('app.tenant_id', '20000000-0000-4000-8000-000000000001', true);
  if (select count(*) from invoice) + (select count(*) from invoice_line) + (select count(*) from payment) <> 0 then
    raise exception 'FT1 failed: other tenant sees fees'; end if;
  perform set_config('app.tenant_id', '', true);
  if (select count(*) from invoice) <> 0 then raise exception 'FT1 failed: visible without tenant'; end if;
  perform set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
  raise notice 'FT1 pass: fees isolated per tenant';
end $$;

-- FT2: total must equal its lines; numbers run in order; a year or student mismatch is refused.
do $$ declare a uuid; b uuid; na text; nb text; begin
  begin
    perform pg_temp.inv(500000, array[300000, 100000]);
    set constraints invoice_total_check immediate;
    raise exception 'FT2 failed: mismatched total accepted';
  exception when check_violation then null; end;
  set constraints invoice_total_check deferred;
  a := pg_temp.inv(1000, array[1000]);
  b := pg_temp.inv(2000, array[2000]);
  select number into na from invoice where id = a;
  select number into nb from invoice where id = b;
  if na !~ '^INV-2026-27-[0-9]{6}$' or na >= nb then raise exception 'FT2 failed: numbers % %', na, nb; end if;
  begin
    insert into invoice (tenant_id, campus_id, academic_year_id, student_person_id, number, due_on, total_paise, issued_by)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            '13000000-0000-4000-8000-000000000021', 'X-1', '2026-12-31', 100, '13000000-0000-4000-8000-000000000001');
    raise exception 'FT2 failed: invoice for a child of another campus accepted';
  exception when check_violation then null; end;
  begin
    insert into invoice (tenant_id, campus_id, academic_year_id, student_person_id, number, due_on, total_paise, issued_by)
    values ('10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
            '13000000-0000-4000-8000-000000000020', 'X-2', '2026-12-31', 0, '13000000-0000-4000-8000-000000000001');
    raise exception 'FT2 failed: zero invoice accepted';
  exception when check_violation then null; end;
  raise notice 'FT2 pass: totals, numbering, enrolment';
end $$;

-- FT3: payments drive the invoice: partial, paid, and never more than the total.
do $$ declare i uuid; begin
  i := pg_temp.inv(500000, array[300000, 200000]);
  perform pg_temp.cash(i, 200000);
  if pg_temp.st(i) <> 'PARTIALLY_PAID:200000' then raise exception 'FT3 failed: %', pg_temp.st(i); end if;
  perform pg_temp.cash(i, 300000);
  if pg_temp.st(i) <> 'PAID:500000' then raise exception 'FT3 failed: %', pg_temp.st(i); end if;
  begin perform pg_temp.cash(i, 1); raise exception 'FT3 failed: overpayment accepted';
  exception when check_violation then null; end;
  if pg_temp.st(i) <> 'PAID:500000' then raise exception 'FT3 failed: overpayment changed the invoice'; end if;
  raise notice 'FT3 pass: partial, paid, no overpayment';
end $$;

-- FT4: cancelling.
do $$ declare i uuid; j uuid; begin
  i := pg_temp.inv(10000, array[10000]);
  begin update invoice set status = 'CANCELLED', cancelled_at = now() where id = i; raise exception 'FT4 failed: cancelled without a reason';
  exception when check_violation then null; end;
  update invoice set status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'Raised in error' where id = i;
  begin perform pg_temp.cash(i, 10000); raise exception 'FT4 failed: payment on a cancelled invoice';
  exception when check_violation then null; end;
  begin update invoice set status = 'ISSUED', cancelled_at = null, cancel_reason = null where id = i; raise exception 'FT4 failed: cancelled invoice revived';
  exception when check_violation then null; end;
  j := pg_temp.inv(10000, array[10000]);
  perform pg_temp.cash(j, 100);
  begin update invoice set status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'Too late' where id = j; raise exception 'FT4 failed: paid invoice cancelled';
  exception when check_violation then null; end;
  raise notice 'FT4 pass: cancel rules';
end $$;

-- FT5: issued invoices are fixed; the application cannot write money fields or payment status directly.
do $$ declare i uuid; p uuid; begin
  i := pg_temp.inv(10000, array[10000]);
  p := pg_temp.cash(i, 100);
  begin update invoice_line set amount_paise = 1 where invoice_id = i; raise exception 'FT5 failed: line edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin delete from invoice_line where invoice_id = i; raise exception 'FT5 failed: line deleted';
  exception when check_violation or insufficient_privilege then null; end;
  begin update invoice set total_paise = 1 where id = i; raise exception 'FT5 failed: total edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin update invoice set paid_paise = 10000 where id = i; raise exception 'FT5 failed: paid edited';
  exception when check_violation or insufficient_privilege then null; end;
  begin update payment set status = 'FAILED' where id = p; raise exception 'FT5 failed: payment edited';
  exception when check_violation or insufficient_privilege then null; end;
  raise notice 'FT5 pass: issued invoices and payments are fixed';
end $$;

-- FT6: idempotency keys and cheque references.
do $$ declare i uuid; begin
  i := pg_temp.inv(10000, array[10000]);
  perform pg_temp.cash(i, 100, 'key-offline-1');
  begin perform pg_temp.cash(i, 100, 'key-offline-1'); raise exception 'FT6 failed: repeated key';
  exception when unique_violation then null; end;
  begin
    insert into payment (tenant_id, invoice_id, amount_paise, method, status, completed_at)
    values ('10000000-0000-4000-8000-000000000001', i, 100, 'CHEQUE', 'SUCCEEDED', now());
    raise exception 'FT6 failed: cheque without a reference';
  exception when check_violation then null; end;
  raise notice 'FT6 pass: idempotency and cheque reference';
end $$;

-- FT7: gateway events apply once and every odd case is recorded, not guessed.
do $$ declare i uuid; j uuid; p uuid; q uuid; r text; begin
  i := pg_temp.inv(100000, array[100000]);
  p := pg_temp.online(i, 100000, 'order_A');
  r := fee_apply_gateway_event('sandbox', 'evt-1', 'order_A', 'pay_A', 100000, 'SUCCEEDED');
  if r <> 'APPLIED' or pg_temp.st(i) <> 'PAID:100000' then raise exception 'FT7 failed: success not applied (% %)', r, pg_temp.st(i); end if;
  if (select gateway_payment_id from payment where id = p) <> 'pay_A' then raise exception 'FT7 failed: payment ref'; end if;
  if fee_apply_gateway_event('sandbox', 'evt-1', 'order_A', 'pay_A', 100000, 'SUCCEEDED') <> 'DUPLICATE' then raise exception 'FT7 failed: replay applied'; end if;
  if fee_apply_gateway_event('sandbox', 'evt-2', 'order_A', 'pay_A', 100000, 'SUCCEEDED') <> 'IGNORED' then raise exception 'FT7 failed: second event for a paid order'; end if;
  if fee_apply_gateway_event('sandbox', 'evt-3', 'order_NOPE', 'pay_X', 100, 'SUCCEEDED') <> 'UNKNOWN_ORDER' then raise exception 'FT7 failed: unknown order'; end if;

  j := pg_temp.inv(100000, array[100000]);
  q := pg_temp.online(j, 100000, 'order_B');
  if fee_apply_gateway_event('sandbox', 'evt-4', 'order_B', 'pay_B', 99999, 'SUCCEEDED') <> 'AMOUNT_MISMATCH' then raise exception 'FT7 failed: mismatch'; end if;
  if (select status from payment where id = q) <> 'PENDING' or pg_temp.st(j) <> 'ISSUED:0' then raise exception 'FT7 failed: mismatch changed state'; end if;
  if fee_apply_gateway_event('sandbox', 'evt-5', 'order_B', null, 100000, 'FAILED') <> 'FAILED_RECORDED' then raise exception 'FT7 failed: failure'; end if;
  if (select status from payment where id = q) <> 'FAILED' then raise exception 'FT7 failed: not failed'; end if;

  -- two orders for the same balance: the second payment cannot be applied and is flagged for refund
  perform pg_temp.online(j, 100000, 'order_C');
  q := pg_temp.online(j, 100000, 'order_D');
  if fee_apply_gateway_event('sandbox', 'evt-6', 'order_C', 'pay_C', 100000, 'SUCCEEDED') <> 'APPLIED' then raise exception 'FT7 failed: first of two'; end if;
  if fee_apply_gateway_event('sandbox', 'evt-7', 'order_D', 'pay_D', 100000, 'SUCCEEDED') <> 'OVERPAYMENT' then raise exception 'FT7 failed: overpayment'; end if;
  if (select status from payment where id = q) <> 'NEEDS_REFUND' or pg_temp.st(j) <> 'PAID:100000' then raise exception 'FT7 failed: overpayment state'; end if;

  if (select count(*) from gateway_event where outcome = 'UNKNOWN_ORDER') <> 0 then raise exception 'FT7 failed: unknown events visible to a school'; end if;
  begin insert into gateway_event (provider, event_id, outcome) values ('x', 'y', 'APPLIED'); raise exception 'FT7 failed: events writable';
  exception when insufficient_privilege then null; end;
  raise notice 'FT7 pass: webhook once-only, mismatch, failure, overpayment, unknown order';
end $$;

rollback;

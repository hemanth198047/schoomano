-- B8 fees and collections (M14). Money is whole paise (bigint), never floating point.
--  * An invoice is issued with fixed lines; its total must equal the sum of its lines (checked at commit) and nothing
--    can change after issue except cancelling an unpaid invoice.
--  * An invoice's paid amount and status are derived from its SUCCEEDED payments by a trigger; paying more than the
--    invoice is refused by the database.
--  * Online payments are PENDING until the gateway's signed webhook says otherwise. Each webhook event is applied at
--    most once (gateway_event is unique per provider and event id), an amount that does not match leaves the payment
--    pending for reconciliation, and money taken for an invoice that can no longer accept it is marked NEEDS_REFUND.
--  * The webhook is the only unauthenticated entry; fee_apply_gateway_event is SECURITY DEFINER and runs as the
--    schema owner (which bypasses RLS in the supplied setup).

create table fee_head (
  id        uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenant(id),
  campus_id uuid not null,
  code      text not null check (code ~ '^[A-Z0-9-]{2,16}$'),
  name      text not null check (length(name) between 2 and 80),
  status    text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  created_at timestamptz not null default now(),
  unique (tenant_id, campus_id, code),
  unique (tenant_id, id),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id)
);

create table invoice_counter (
  tenant_id        uuid not null references tenant(id),
  academic_year_id uuid not null,
  last_number      integer not null default 0,
  primary key (tenant_id, academic_year_id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id)
);

create table invoice (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenant(id),
  campus_id         uuid not null,
  academic_year_id  uuid not null,
  student_person_id uuid not null,
  number            text not null,
  status            text not null default 'ISSUED' check (status in ('ISSUED','PARTIALLY_PAID','PAID','CANCELLED')),
  due_on            date not null,
  total_paise       bigint not null check (total_paise > 0),
  paid_paise        bigint not null default 0 check (paid_paise >= 0 and paid_paise <= total_paise),
  batch_key         text,
  issued_by         uuid not null,
  issued_at         timestamptz not null default now(),
  cancelled_at      timestamptz,
  cancel_reason     text,
  unique (tenant_id, number),
  unique (tenant_id, id),
  unique (tenant_id, batch_key, student_person_id),
  check ((status = 'CANCELLED') = (cancelled_at is not null)),
  check (status <> 'PAID' or paid_paise = total_paise),
  foreign key (tenant_id, campus_id) references campus(tenant_id, id),
  foreign key (tenant_id, academic_year_id) references academic_year(tenant_id, id),
  foreign key (tenant_id, student_person_id) references person(tenant_id, id),
  foreign key (tenant_id, issued_by) references person(tenant_id, id)
);
create index invoice_student_idx on invoice (tenant_id, student_person_id);
create index invoice_year_status_idx on invoice (tenant_id, academic_year_id, status);

create table invoice_line (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenant(id),
  invoice_id   uuid not null,
  fee_head_id  uuid,
  description  text not null check (length(description) between 2 and 120),
  amount_paise bigint not null check (amount_paise > 0),
  foreign key (tenant_id, invoice_id) references invoice(tenant_id, id),
  foreign key (tenant_id, fee_head_id) references fee_head(tenant_id, id)
);
create index invoice_line_invoice_idx on invoice_line (invoice_id);

create table payment (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references tenant(id),
  invoice_id         uuid not null,
  amount_paise       bigint not null check (amount_paise > 0),
  method             text not null check (method in ('GATEWAY','CASH','CHEQUE','UPI_OFFICE')),
  status             text not null check (status in ('PENDING','SUCCEEDED','FAILED','NEEDS_REFUND')),
  gateway_order_id   text,
  gateway_payment_id text,
  reference          text,
  idempotency_key    text check (idempotency_key is null or length(idempotency_key) between 8 and 100),
  created_by         uuid,
  created_at         timestamptz not null default now(),
  completed_at       timestamptz,
  unique (tenant_id, id),
  foreign key (tenant_id, invoice_id) references invoice(tenant_id, id),
  foreign key (tenant_id, created_by) references person(tenant_id, id),
  check ((method = 'GATEWAY') = (gateway_order_id is not null)),
  check (method = 'GATEWAY' or status = 'SUCCEEDED'),
  check (method <> 'CHEQUE' or reference is not null),
  check ((status = 'PENDING') = (completed_at is null))
);
create unique index payment_idem_idx on payment (tenant_id, idempotency_key) where idempotency_key is not null;
create unique index payment_order_idx on payment (gateway_order_id) where gateway_order_id is not null;
create unique index payment_gateway_payment_idx on payment (gateway_payment_id) where gateway_payment_id is not null;
create index payment_invoice_idx on payment (invoice_id);

create table gateway_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid references tenant(id),            -- null when the order is not ours
  provider       text not null,
  event_id       text not null,
  order_id       text,
  payment_ref    text,
  amount_paise   bigint,
  claimed_status text,
  outcome        text not null,
  received_at    timestamptz not null default now(),
  unique (provider, event_id)
);

-- Numbers run per school and year: INV-2026-27-000001.
create function fee_next_invoice_number(p_year uuid) returns text
  language plpgsql security definer set search_path = public as $$
declare y academic_year%rowtype; n integer;
begin
  select * into y from academic_year where id = p_year;
  if not found or y.tenant_id is distinct from app_current_tenant() then
    raise exception 'academic year not found' using errcode = 'check_violation';
  end if;
  insert into invoice_counter (tenant_id, academic_year_id, last_number) values (y.tenant_id, y.id, 1)
  on conflict (tenant_id, academic_year_id) do update set last_number = invoice_counter.last_number + 1
  returning last_number into n;
  return 'INV-' || y.label || '-' || lpad(n::text, 6, '0');
end $$;
revoke all on function fee_next_invoice_number(uuid) from public;
grant execute on function fee_next_invoice_number(uuid) to schoomano_app;

-- An invoice is for a child enrolled in that year at that campus, and its year must be open.
create function invoice_insert_guard() returns trigger language plpgsql as $$
declare y academic_year%rowtype;
begin
  select * into y from academic_year where id = new.academic_year_id;
  if y.campus_id <> new.campus_id then
    raise exception 'the academic year belongs to a different campus' using errcode = 'check_violation';
  end if;
  if y.status = 'CLOSED' then
    raise exception 'the academic year is closed' using errcode = 'check_violation';
  end if;
  if not exists (select 1 from student_enrollment e where e.student_person_id = new.student_person_id
                  and e.academic_year_id = new.academic_year_id and e.campus_id = new.campus_id) then
    raise exception 'the student is not enrolled in this year at this campus' using errcode = 'check_violation';
  end if;
  if new.status <> 'ISSUED' or new.paid_paise <> 0 then
    raise exception 'an invoice starts issued and unpaid' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger invoice_insert_guard before insert on invoice for each row execute function invoice_insert_guard();

-- Total equals the sum of the lines, checked once everything is inserted (at commit).
create function invoice_total_check() returns trigger language plpgsql as $$
declare s bigint;
begin
  select coalesce(sum(amount_paise), 0) into s from invoice_line where invoice_id = new.id;
  if s <> new.total_paise then
    raise exception 'invoice total % does not match its lines %', new.total_paise, s using errcode = 'check_violation';
  end if;
  return null;
end $$;
create constraint trigger invoice_total_check after insert on invoice
  deferrable initially deferred for each row execute function invoice_total_check();

-- After issue, lines never change, and an invoice changes only by cancelling it unpaid (or by its payments).
create function invoice_line_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'invoice lines cannot be changed after issue' using errcode = 'check_violation';
end $$;
create trigger invoice_line_immutable before update or delete on invoice_line for each row execute function invoice_line_immutable();

create function invoice_update_guard() returns trigger language plpgsql as $$
begin
  if new.total_paise <> old.total_paise or new.student_person_id <> old.student_person_id or new.number <> old.number
     or new.academic_year_id <> old.academic_year_id or new.campus_id <> old.campus_id or new.due_on <> old.due_on then
    raise exception 'an issued invoice cannot be edited' using errcode = 'check_violation';
  end if;
  if (new.paid_paise <> old.paid_paise or (new.status <> old.status and new.status <> 'CANCELLED'))
     and coalesce(current_setting('app.fee_internal', true), '') <> '1' then
    raise exception 'paid amount and status follow the payments' using errcode = 'check_violation';
  end if;
  if new.status = 'CANCELLED' and old.status <> 'CANCELLED' then
    if old.status <> 'ISSUED' or old.paid_paise <> 0 then
      raise exception 'only an unpaid invoice can be cancelled' using errcode = 'check_violation';
    end if;
    if new.cancel_reason is null or length(new.cancel_reason) < 3 then
      raise exception 'a reason is required to cancel' using errcode = 'check_violation';
    end if;
  elsif old.status = 'CANCELLED' then
    raise exception 'a cancelled invoice is final' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger invoice_update_guard before update on invoice for each row execute function invoice_update_guard();

-- Payments: only on live invoices; a pending payment may only become succeeded, failed or needs-refund, once.
create function payment_guard() returns trigger language plpgsql as $$
declare inv invoice%rowtype;
begin
  if tg_op = 'INSERT' then
    select * into inv from invoice where id = new.invoice_id;
    if inv.status = 'CANCELLED' then
      raise exception 'the invoice is cancelled' using errcode = 'check_violation';
    end if;
    if new.status not in ('PENDING', 'SUCCEEDED') then
      raise exception 'a payment starts pending or succeeded' using errcode = 'check_violation';
    end if;
  else
    if old.status <> 'PENDING' then
      raise exception 'a completed payment cannot change' using errcode = 'check_violation';
    end if;
    if new.amount_paise <> old.amount_paise or new.invoice_id <> old.invoice_id or new.method <> old.method then
      raise exception 'payment details cannot change' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end $$;
create trigger payment_guard before insert or update on payment for each row execute function payment_guard();

-- The invoice follows its succeeded payments. Runs as the owner so the application need not (and cannot) set these.
create function payment_apply() returns trigger language plpgsql security definer set search_path = public as $$
declare inv invoice%rowtype; s bigint;
begin
  if new.status <> 'SUCCEEDED' then
    return null;
  end if;
  select * into inv from invoice where id = new.invoice_id for update;
  select coalesce(sum(amount_paise), 0) into s from payment where invoice_id = new.invoice_id and status = 'SUCCEEDED';
  if s > inv.total_paise then
    raise exception 'the payment would exceed the invoice' using errcode = 'check_violation';
  end if;
  perform set_config('app.fee_internal', '1', true);
  update invoice set paid_paise = s,
         status = case when s = total_paise then 'PAID' when s > 0 then 'PARTIALLY_PAID' else 'ISSUED' end
   where id = inv.id;
  perform set_config('app.fee_internal', '', true);
  return null;
end $$;
create trigger payment_apply after insert or update on payment for each row execute function payment_apply();

-- Applies one gateway event, once. Outcomes: APPLIED, FAILED_RECORDED, DUPLICATE, UNKNOWN_ORDER, AMOUNT_MISMATCH,
-- OVERPAYMENT (money taken that the invoice cannot accept: needs a refund), IGNORED.
create function fee_apply_gateway_event(p_provider text, p_event text, p_order text, p_payment text, p_amount bigint, p_status text)
  returns text language plpgsql security definer set search_path = public as $$
declare pay payment%rowtype; v_out text; ev uuid;
begin
  insert into gateway_event (provider, event_id, order_id, payment_ref, amount_paise, claimed_status, outcome)
  values (p_provider, p_event, p_order, p_payment, p_amount, p_status, 'RECEIVED')
  on conflict (provider, event_id) do nothing returning id into ev;
  if ev is null then
    return 'DUPLICATE';
  end if;
  select * into pay from payment where gateway_order_id = p_order for update;
  if not found then
    v_out := 'UNKNOWN_ORDER';
  else
    update gateway_event set tenant_id = pay.tenant_id where id = ev;
    if pay.status <> 'PENDING' then
      v_out := 'IGNORED';
    elsif p_status = 'FAILED' then
      update payment set status = 'FAILED', completed_at = now() where id = pay.id;
      v_out := 'FAILED_RECORDED';
    elsif p_status = 'SUCCEEDED' and p_amount is distinct from pay.amount_paise then
      v_out := 'AMOUNT_MISMATCH';
    elsif p_status = 'SUCCEEDED' then
      begin
        update payment set status = 'SUCCEEDED', completed_at = now(), gateway_payment_id = p_payment where id = pay.id;
        v_out := 'APPLIED';
      exception when check_violation then
        update payment set status = 'NEEDS_REFUND', completed_at = now(), gateway_payment_id = p_payment where id = pay.id;
        v_out := 'OVERPAYMENT';
      end;
    else
      v_out := 'IGNORED';
    end if;
  end if;
  update gateway_event set outcome = v_out where id = ev;
  return v_out;
end $$;
revoke all on function fee_apply_gateway_event(text, text, text, text, bigint, text) from public;
revoke all on function fee_apply_gateway_event(text, text, text, text, bigint, text) from schoomano_app;
grant execute on function fee_apply_gateway_event(text, text, text, text, bigint, text) to schoomano_app;

do $$
declare t text;
begin
  foreach t in array array['fee_head','invoice_counter','invoice','invoice_line','payment','gateway_event']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant())', t);
  end loop;
end $$;

grant select, insert, update (status) on fee_head to schoomano_app;
grant select, insert on invoice_line to schoomano_app;
grant select, insert, update (status, cancelled_at, cancel_reason) on invoice to schoomano_app;
grant select, insert on payment to schoomano_app;
grant select on gateway_event to schoomano_app;

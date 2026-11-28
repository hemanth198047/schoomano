-- B35 / M26: reminders and escalation. A request that waits too long at its current step is nudged once as a reminder and
-- once more as an escalation, each recorded permanently so nobody is told twice about the same step. Nudges only inform;
-- they never change who may decide.

create table workflow_nudge (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references tenant(id),
  request_id uuid not null,
  position   int not null check (position between 1 and 8),
  kind       text not null check (kind in ('REMINDER', 'ESCALATION')),
  sent_at    timestamptz not null default clock_timestamp(),
  unique (tenant_id, request_id, position, kind),
  foreign key (tenant_id, request_id) references workflow_request(tenant_id, id)
);

alter table workflow_nudge enable row level security;
alter table workflow_nudge force row level security;
create policy tenant_isolation on workflow_nudge using (tenant_id = app_current_tenant()) with check (tenant_id = app_current_tenant());
grant select, insert on workflow_nudge to schoomano_app;

create function workflow_nudge_guard() returns trigger language plpgsql as $$
declare r record;
begin
  if tg_op <> 'INSERT' then raise exception 'nudges are permanent' using errcode = 'check_violation'; end if;
  select status, current_step into r from workflow_request where tenant_id = new.tenant_id and id = new.request_id;
  if r.status <> 'PENDING' or r.current_step <> new.position then
    raise exception 'only the current step of a pending request is nudged' using errcode = 'check_violation'; end if;
  return new;
end $$;
create trigger workflow_nudge_check before insert or update or delete on workflow_nudge for each row execute function workflow_nudge_guard();

-- Pending requests that have waited at their current step at least p_remind (reminder) or p_escalate (escalation) and have
-- not been nudged for that step yet. The wait starts at submission or at the last approval. A request that is due for an
-- escalation is not also reminded. Reads every school, so the sender can then work school by school.
create function workflow_overdue(p_remind interval, p_escalate interval)
  returns table (t_id uuid, req_id uuid, step_no integer, nudge_kind text)
  language sql stable security definer set search_path = public as $$
  with w as (
    select r.tenant_id, r.id, r.current_step,
           coalesce((select max(x.decided_at) from workflow_decision x where x.tenant_id = r.tenant_id and x.request_id = r.id), r.submitted_at) as since
      from workflow_request r where r.status = 'PENDING'
  )
  select w.tenant_id, w.id, w.current_step, k.kind
    from w cross join lateral (values ('REMINDER', p_remind), ('ESCALATION', p_escalate)) as k(kind, after)
   where clock_timestamp() - w.since >= k.after
     and not (k.kind = 'REMINDER' and clock_timestamp() - w.since >= p_escalate)
     and not exists (select 1 from workflow_nudge n where n.tenant_id = w.tenant_id and n.request_id = w.id and n.position = w.current_step and n.kind = k.kind)
   order by w.since limit 200
$$;
revoke all on function workflow_overdue(interval, interval) from public;
grant execute on function workflow_overdue(interval, interval) to schoomano_app;

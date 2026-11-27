-- B27 / M26 + M16: a leave request may be routed through an approval chain. A linked request is approved only once its
-- workflow request is approved, rejected once it is rejected, and cancelled once it is withdrawn.

alter table leave_request add column workflow_request_id uuid;
alter table leave_request add constraint leave_request_workflow_fk
  foreign key (tenant_id, workflow_request_id) references workflow_request(tenant_id, id);
create unique index leave_request_one_per_workflow on leave_request (workflow_request_id) where workflow_request_id is not null;

create function leave_workflow_guard() returns trigger language plpgsql as $$
declare w workflow_request%rowtype;
begin
  if tg_op = 'INSERT' then
    if new.workflow_request_id is null then return new; end if;
    select * into w from workflow_request where tenant_id = new.tenant_id and id = new.workflow_request_id;
    if w.requester_id is distinct from new.person_id or w.campus_id <> new.campus_id or w.status <> 'PENDING' then
      raise exception 'the approval request must be an open one raised by the same person on the same campus' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.workflow_request_id is distinct from old.workflow_request_id then
    raise exception 'the approval chain of a request cannot change' using errcode = 'check_violation';
  end if;
  if old.workflow_request_id is null or new.status = old.status then return new; end if;
  select * into w from workflow_request where tenant_id = new.tenant_id and id = new.workflow_request_id;
  if (new.status = 'APPROVED' and w.status <> 'APPROVED') or (new.status = 'REJECTED' and w.status <> 'REJECTED')
     or (new.status = 'CANCELLED' and w.status <> 'WITHDRAWN') then
    raise exception 'this request is decided through its approval chain' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger leave_request_workflow before insert or update on leave_request
  for each row execute function leave_workflow_guard();

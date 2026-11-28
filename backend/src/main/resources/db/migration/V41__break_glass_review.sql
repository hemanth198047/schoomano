-- B44 / M30: break-glass access can now be requested and must be reviewed. A member of staff who needs emergency access
-- to a restricted health or safeguarding record states why (at least 20 characters) and gets it for at most four hours;
-- someone else with authority over the audit trail then reviews it: appropriate, inappropriate or escalated. A grant cannot be
-- edited after it is made, cannot be reviewed by the person it was made to, is reviewed once, and an inappropriate or
-- escalated review ends any access that is still running at once.

alter table break_glass_grant
  add column review_note text,
  add constraint break_glass_type check (resource_type in ('health_record', 'safeguarding_case')),
  add constraint break_glass_review_whole check (
    (reviewed_by is null) = (reviewed_at is null) and (reviewed_by is null) = (review_outcome is null)
    and (reviewed_by is null or char_length(btrim(coalesce(review_note, ''))) >= 10));
create index break_glass_by_tenant on break_glass_grant (tenant_id, granted_at desc);

revoke update on break_glass_grant from schoomano_app;
grant update (reviewed_by, reviewed_at, review_outcome, review_note) on break_glass_grant to schoomano_app;

create function break_glass_guard() returns trigger language plpgsql as $$
begin
  if (new.tenant_id, new.person_id, new.resource_type, new.resource_id, new.justification, new.granted_at)
       is distinct from (old.tenant_id, old.person_id, old.resource_type, old.resource_id, old.justification, old.granted_at) then
    raise exception 'a break-glass grant cannot be changed' using errcode = 'check_violation';
  end if;
  if old.reviewed_at is not null then
    raise exception 'this break-glass grant has already been reviewed' using errcode = 'check_violation';
  end if;
  if new.reviewed_by is not null and new.reviewed_by = old.person_id then
    raise exception 'a break-glass grant cannot be reviewed by the person who used it' using errcode = 'check_violation';
  end if;
  if new.reviewed_by is not null and new.review_outcome <> 'APPROPRIATE' then
    new.expires_at := least(old.expires_at, greatest(now(), old.granted_at + interval '1 second'));
  else
    new.expires_at := old.expires_at;
  end if;
  return new;
end $$;
create trigger break_glass_guard_trg before update on break_glass_grant for each row execute function break_glass_guard();

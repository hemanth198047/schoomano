-- B31 / M21: staff notices carry their own kind, so a visitor arrival is not filed as an approval. In-app only, as before.

alter table notice drop constraint notice_kind_check;
alter table notice add constraint notice_kind_check check (kind in ('ANNOUNCEMENT','ATTENDANCE','SUBSTITUTION','APPROVAL','VISITOR'));

drop function notice_tell_staff(uuid, text, text, uuid[], uuid);

create function notice_tell_staff(p_kind text, p_campus uuid, p_title text, p_body text, p_recipients uuid[], p_created_by uuid)
  returns integer language plpgsql security definer set search_path = public as $$
declare n uuid; r uuid; total integer := 0; t uuid := app_current_tenant(); people uuid[];
begin
  if t is null then raise exception 'no school selected' using errcode = 'check_violation'; end if;
  if p_kind not in ('APPROVAL','VISITOR') then raise exception 'not a staff notice kind' using errcode = 'check_violation'; end if;
  if p_recipients is null or cardinality(p_recipients) = 0
     or not exists (select 1 from campus where tenant_id = t and id = p_campus) then
    return 0;
  end if;
  select array_agg(id) into people from person where tenant_id = t and status = 'ACTIVE' and id = any (p_recipients);
  if people is null then return 0; end if;
  insert into notice (tenant_id, campus_id, kind, title, body, sms_text, created_by)
    values (t, p_campus, p_kind, left(p_title, 120), left(p_body, 2000), left(p_title, 160), p_created_by) returning id into n;
  foreach r in array people loop
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, channel, status, sent_at)
      values (t, n, r, 'IN_APP', 'SENT', now()) on conflict do nothing;
    insert into notice_delivery (tenant_id, notice_id, recipient_person_id, channel, status, skipped_reason)
      values (t, n, r, 'SMS', 'SKIPPED', 'staff notices are in-app only') on conflict do nothing;
    total := total + 1;
  end loop;
  return total;
end $$;
revoke all on function notice_tell_staff(text, uuid, text, text, uuid[], uuid) from public;
grant execute on function notice_tell_staff(text, uuid, text, text, uuid[], uuid) to schoomano_app;

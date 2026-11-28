-- B55 / M26: whoever decides a step must hold the step's role for the request's campus (a grant for the whole group, the
-- campus's legal entity, or that campus), not just hold the role somewhere. Before this, the database only asked whether the
-- person held the role at all, so a principal of another campus passed it. The grant must also have started and not ended.
create function workflow_decider_scope() returns trigger language plpgsql as $$
declare r workflow_request; want text; entity uuid;
begin
  select * into r from workflow_request where tenant_id = new.tenant_id and id = new.request_id;
  select s.role_key into want from workflow_step s where s.tenant_id = new.tenant_id and s.definition_id = r.definition_id and s.position = new.position;
  select legal_entity_id into entity from campus where tenant_id = new.tenant_id and id = r.campus_id;
  if not exists (
    select 1 from role_grant g
     where g.tenant_id = new.tenant_id and g.person_id = new.decider_id and g.role_key = want
       and g.valid_from <= now() and (g.valid_to is null or g.valid_to > now())
       and (g.scope_level = 'GROUP'
            or (g.scope_level = 'CAMPUS' and g.scope_id = r.campus_id)
            or (g.scope_level = 'LEGAL_ENTITY' and g.scope_id = entity))) then
    raise exception 'the decider does not hold the role for this step at this campus' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger workflow_decision_scope before insert on workflow_decision for each row execute function workflow_decider_scope();

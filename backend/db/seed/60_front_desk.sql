-- B29 dev seed (synthetic): the front-desk officer for North campus. Idempotent.
insert into person (id, tenant_id, school_ref, given_name, family_name) values
  ('13000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000001', 'STF-0006', 'Fran', 'Front desk (synthetic)')
on conflict do nothing;
insert into identity (tenant_id, person_id, issuer, subject, email) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000006', 'dev', 'dev-fran', 'fran@example.invalid')
on conflict do nothing;
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
select '10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000006', 'front_desk', 'CAMPUS',
       '12000000-0000-4000-8000-000000000001', 'seed'
 where not exists (select 1 from role_grant where person_id = '13000000-0000-4000-8000-000000000006' and role_key = 'front_desk');

-- SYNTHETIC contact details and households (B3). Runs after 10_base.sql.
-- Re-runnable: does nothing once any guardian has a mobile number, or if the base seed is missing.

select (not exists (select 1 from tenant where id = '10000000-0000-4000-8000-000000000001')
        or exists (select 1 from person where mobile_e164 is not null)) as skip_people \gset
\if :skip_people
\echo 'People seed already present (or base seed missing); nothing to do.'
\quit
\endif

begin;

-- 98000 0xxxx numbers are synthetic; never use real numbers in seeds.
update person set mobile_e164 = '+919800000010', email = 'meera@example.invalid'
 where id = '13000000-0000-4000-8000-000000000010';
update person set mobile_e164 = '+919800000011', email = 'karan@example.invalid'
 where id = '13000000-0000-4000-8000-000000000011';

-- Meera lives with both children; Karan (separated, restricted) has his own household.
insert into household (id, tenant_id, name) values
  ('16100000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Meera''s household (synthetic)'),
  ('16100000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'Karan''s household (synthetic)');
insert into household_member (tenant_id, household_id, person_id, member_role) values
  ('10000000-0000-4000-8000-000000000001', '16100000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', 'GUARDIAN'),
  ('10000000-0000-4000-8000-000000000001', '16100000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', 'STUDENT'),
  ('10000000-0000-4000-8000-000000000001', '16100000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', 'STUDENT'),
  ('10000000-0000-4000-8000-000000000001', '16100000-0000-4000-8000-000000000002', '13000000-0000-4000-8000-000000000011', 'GUARDIAN');

update person set date_of_birth = '2016-05-12' where id = '13000000-0000-4000-8000-000000000020';
update person set date_of_birth = '2018-11-03' where id = '13000000-0000-4000-8000-000000000021';

commit;

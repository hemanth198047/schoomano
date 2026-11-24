-- B5 dev seed (synthetic): a bell schedule and rooms for North campus, and a second teacher so substitutions can be
-- tried. Idempotent (the start scripts may run the seeds more than once).
insert into period (id, tenant_id, campus_id, ordinal, label, starts_at, ends_at, is_break)
select md5('period-north-' || p.ordinal)::uuid, '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001',
       p.ordinal, p.label, p.s::time, p.e::time, p.brk
  from (values (1,'Period 1','08:30','09:15',false), (2,'Period 2','09:15','10:00',false),
               (3,'Short break','10:00','10:15',true), (4,'Period 3','10:15','11:00',false),
               (5,'Period 4','11:00','11:45',false), (6,'Lunch','11:45','12:30',true),
               (7,'Period 5','12:30','13:15',false), (8,'Period 6','13:15','14:00',false)) p(ordinal, label, s, e, brk)
on conflict do nothing;

insert into room (id, tenant_id, campus_id, code, name, capacity)
select md5('room-north-' || r.code)::uuid, '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', r.code, r.name, r.cap
  from (values ('R101','Room 101',40), ('R102','Room 102',40), ('LAB1','Science lab',30)) r(code, name, cap)
on conflict do nothing;

insert into person (id, tenant_id, school_ref, given_name, family_name) values
  ('13000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', 'STF-0004', 'Sunita', 'Teacher (synthetic)')
on conflict do nothing;
insert into identity (tenant_id, person_id, issuer, subject, email) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004', 'dev', 'dev-sunita', 'sunita@example.invalid')
on conflict do nothing;
insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
select '10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000004', 'teacher', 'CAMPUS',
       '12000000-0000-4000-8000-000000000001', 'seed'
 where not exists (select 1 from role_grant where person_id = '13000000-0000-4000-8000-000000000004' and role_key = 'teacher');

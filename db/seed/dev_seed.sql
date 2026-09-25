-- SYNTHETIC development data only. Names are invented. Never load real student data here.
-- Run as the migration owner or a superuser (bypasses RLS for seeding), after Flyway migrations.
-- Fixed UUIDs keep dev logins and tests predictable.

begin;

insert into tenant (id, slug, display_name) values
  ('10000000-0000-4000-8000-000000000001', 'demo-group',  'Nilgiri Learning Trust (synthetic)'),
  ('20000000-0000-4000-8000-000000000001', 'other-group', 'Other School Group (synthetic)');

insert into legal_entity (id, tenant_id, name) values
  ('11000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Nilgiri Education Society (synthetic)'),
  ('21000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'Other Education Society (synthetic)');

insert into campus (id, tenant_id, legal_entity_id, code, name) values
  ('12000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000001', 'BLR-N', 'North Campus'),
  ('12000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000001', 'BLR-S', 'South Campus'),
  ('22000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', '21000000-0000-4000-8000-000000000001', 'OTH-1', 'Other Campus');

insert into programme (id, tenant_id, campus_id, code, name, board) values
  ('14000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', 'CBSE-K12', 'CBSE Grades 1–12', 'CBSE'),
  ('14000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000002', 'IB-PYP', 'IB Primary Years', 'IB');

insert into academic_year (id, tenant_id, campus_id, label, starts_on, ends_on, status) values
  ('15000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000001', '2026-27', '2026-04-01', '2027-03-31', 'ACTIVE'),
  ('15000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '12000000-0000-4000-8000-000000000002', '2026-27', '2026-06-01', '2027-05-31', 'ACTIVE');

insert into person (id, tenant_id, school_ref, given_name, family_name) values
  ('13000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'STF-0001', 'Asha',   'Admin (synthetic)'),
  ('13000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'STF-0002', 'Ravi',   'Teacher (synthetic)'),
  ('13000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', 'STF-0003', 'Farah',  'Safeguarding Lead (synthetic)'),
  ('13000000-0000-4000-8000-000000000010', '10000000-0000-4000-8000-000000000001', 'GRD-0010', 'Meera',  'Guardian (synthetic)'),
  ('13000000-0000-4000-8000-000000000011', '10000000-0000-4000-8000-000000000001', 'GRD-0011', 'Karan',  'Guardian (synthetic)'),
  ('13000000-0000-4000-8000-000000000020', '10000000-0000-4000-8000-000000000001', 'STU-0020', 'Anaya',  'Student (synthetic)'),
  ('13000000-0000-4000-8000-000000000021', '10000000-0000-4000-8000-000000000001', 'STU-0021', 'Vivaan', 'Student (synthetic)'),
  ('23000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'STU-9001', 'Other',  'Tenant Student (synthetic)');

insert into identity (tenant_id, person_id, issuer, subject, email) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'dev', 'dev-asha',   'asha@example.invalid'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'dev', 'dev-ravi',   'ravi@example.invalid'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'dev', 'dev-farah',  'farah@example.invalid'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', 'dev', 'dev-meera',  'meera@example.invalid'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000011', 'dev', 'dev-karan',  'karan@example.invalid'),
  ('20000000-0000-4000-8000-000000000001', '23000000-0000-4000-8000-000000000001', 'dev', 'dev-other',  'other@example.invalid');

insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'group_admin',       'GROUP',  '10000000-0000-4000-8000-000000000001', 'seed'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000002', 'teacher',           'CAMPUS', '12000000-0000-4000-8000-000000000001', 'seed'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000003', 'safeguarding_lead', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'seed');

insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000020', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000021', '12000000-0000-4000-8000-000000000002', '15000000-0000-4000-8000-000000000002', '14000000-0000-4000-8000-000000000002');

-- Meera: guardian of two children on different campuses (M01 acceptance scenario).
-- Karan: separated guardian of Anaya; receives notices only, sees no academic record, cannot pay or consent.
insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type,
    can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices, can_pickup, status) values
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', '13000000-0000-4000-8000-000000000020', 'MOTHER', true,  true,  true,  true,  true, true,  'ACTIVE'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000010', '13000000-0000-4000-8000-000000000021', 'MOTHER', true,  true,  true,  true,  true, true,  'ACTIVE'),
  ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000011', '13000000-0000-4000-8000-000000000020', 'FATHER', false, false, false, false, true, false, 'RESTRICTED');

insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value, effective_from, status, change_note, created_by, published_by, published_at) values
  ('10000000-0000-4000-8000-000000000001', 'attendance.late_after_minutes', 'GROUP',  '10000000-0000-4000-8000-000000000001', 1, '10'::jsonb,    '2026-04-01T00:00:00+05:30', 'PUBLISHED', 'Group default', '13000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', now()),
  ('10000000-0000-4000-8000-000000000001', 'attendance.late_after_minutes', 'CAMPUS', '12000000-0000-4000-8000-000000000002', 1, '15'::jsonb,    '2026-06-01T00:00:00+05:30', 'PUBLISHED', 'South campus buses arrive later', '13000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', now()),
  ('10000000-0000-4000-8000-000000000001', 'flag.ai.enabled',               'GROUP',  '10000000-0000-4000-8000-000000000001', 1, 'false'::jsonb, '2026-04-01T00:00:00+05:30', 'PUBLISHED', 'AI off by default', '13000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', now());

commit;

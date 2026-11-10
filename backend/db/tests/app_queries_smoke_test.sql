-- Executes every SQL statement used by the Spring layer, as the app role with RLS on.
-- Guards against drift between Java query strings and the schema. Rolls back.
\set ON_ERROR_STOP on
begin;
set local role schoomano_app;

-- RequestContextResolver
select person_id, tenant_id, tenant_slug, tenant_name from auth_resolve_identity('dev', 'dev-meera');

select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

-- ActorLoader
select role_key, scope_level, scope_id, valid_from, valid_to from role_grant
 where person_id = '13000000-0000-4000-8000-000000000001' and (valid_to is null or valid_to > now());
select student_person_id, status, can_view_attendance, can_view_academic, can_pay_fees,
       can_give_consent, can_receive_notices, can_pickup, valid_from, valid_to
  from guardian_relationship
 where guardian_person_id = '13000000-0000-4000-8000-000000000010' and status in ('ACTIVE','RESTRICTED');
select resource_type, resource_id, justification, granted_at, expires_at from break_glass_grant
 where person_id = '13000000-0000-4000-8000-000000000001' and expires_at > now();

-- MeController
select coalesce(preferred_name, given_name) || coalesce(' ' || family_name, '') from person
 where id = '13000000-0000-4000-8000-000000000010';
select coalesce(p.preferred_name, p.given_name) as display_name, p.school_ref, c.id as campus_id, c.name as campus_name
  from person p
  left join lateral (
    select e.campus_id from student_enrollment e join academic_year y on y.id = e.academic_year_id
     where e.student_person_id = p.id order by y.starts_on desc limit 1) le on true
  left join campus c on c.id = le.campus_id
 where p.id = '13000000-0000-4000-8000-000000000020';

-- CampusController + ScopeDirectory
select c.id, c.code, c.name, c.time_zone, c.status, le.name as legal_entity,
       (select y.label from academic_year y where y.campus_id = c.id and y.status = 'ACTIVE'
         order by y.starts_on desc limit 1) as active_year
  from campus c join legal_entity le on le.id = c.legal_entity_id order by c.name;
select legal_entity_id from campus where id = '12000000-0000-4000-8000-000000000001';
select e.campus_id from student_enrollment e join academic_year y on y.id = e.academic_year_id
 where e.student_person_id = '13000000-0000-4000-8000-000000000020' order by y.starts_on desc limit 1;

-- ConfigController
select config_key, scope_level, scope_id, version, value::text, effective_from, effective_to, status
  from config_version where config_key = 'attendance.late_after_minutes';
select id, scope_level, scope_id, version, value::text, effective_from, effective_to, status, change_note, published_at
  from config_version where config_key = 'attendance.late_after_minutes' order by scope_level, scope_id, version;
select coalesce(max(version), 0) + 1 from config_version
 where config_key = 'attendance.late_after_minutes' and scope_level = 'GROUP'
   and scope_id = '10000000-0000-4000-8000-000000000001';

create temp table t_draft on commit drop as
with ins as (
  insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value,
                              effective_from, change_note, created_by)
  values ('10000000-0000-4000-8000-000000000001', 'attendance.late_after_minutes', 'GROUP',
          '10000000-0000-4000-8000-000000000001', 99, '5'::jsonb, '2031-01-01T00:00:00+05:30',
          'smoke test', '13000000-0000-4000-8000-000000000001') returning id)
select id from ins;
select config_publish((select id from t_draft), '13000000-0000-4000-8000-000000000001')::text;

-- AuditWriter + outbox
insert into audit_event (tenant_id, actor_person_id, actor_kind, action, resource_type, resource_id,
                         outcome, reason, correlation_id, policy_version, details)
values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'USER',
        'config.published', 'config_version', 'x', 'SUCCESS', null, 'corr-smoke-01', 'access-2026.09.1', '{"k":1}'::jsonb);
insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
values ('10000000-0000-4000-8000-000000000001', 'config', 'attendance.late_after_minutes', 'config.published',
        '{"key":"attendance.late_after_minutes","version":99}'::jsonb);

-- AcademicController (B1)
select programme_id from grade_level where id = md5('grade-CBSE-K12-5')::uuid;
select campus_id from programme where id = '14000000-0000-4000-8000-000000000001';
select id, campus_id, label, starts_on, ends_on, status from academic_year
 where campus_id = '12000000-0000-4000-8000-000000000001' order by starts_on desc;
select id, code, name, board, board_detail from programme where campus_id = '12000000-0000-4000-8000-000000000001' order by code;
select id, code, name, ordinal from grade_level where programme_id = '14000000-0000-4000-8000-000000000001' order by ordinal;
select s.grade_level_id, s.id, s.code, s.capacity,
       (select count(*) from student_enrollment e where e.class_section_id = s.id and e.status = 'ENROLLED')
  from class_section s join grade_level g on g.id = s.grade_level_id
 where s.academic_year_id = '15000000-0000-4000-8000-000000000001' and g.programme_id = '14000000-0000-4000-8000-000000000001';
select id, code, name, status from subject where programme_id = '14000000-0000-4000-8000-000000000001' order by code;
select id, kind, title, starts_on, ends_on, programme_id from calendar_event
 where academic_year_id = '15000000-0000-4000-8000-000000000001' order by starts_on, title;
select g.id, g.programme_id, p.code, g.code, g.ordinal
  from grade_level g join programme p on p.id = g.programme_id where p.campus_id = '12000000-0000-4000-8000-000000000001';
select p.code, t.label, t.starts_on, t.ends_on
  from academic_term t join programme p on p.id = t.programme_id where t.academic_year_id = '15000000-0000-4000-8000-000000000001';
insert into grade_level (tenant_id, programme_id, code, name, ordinal)
values ('10000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001', 'NUR', 'Nursery', 0) returning id;
insert into subject (tenant_id, programme_id, code, name)
values ('10000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001', 'ART', 'Art') returning id;
insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
        '14000000-0000-4000-8000-000000000003', 'Bridge', '2026-04-01', '2026-04-30') returning id;
insert into class_section (tenant_id, academic_year_id, grade_level_id, code, capacity)
values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
        md5('grade-CBSE-K12-5')::uuid, 'Z', 30) returning id;
insert into calendar_event (tenant_id, academic_year_id, programme_id, kind, title, starts_on, ends_on)
values ('10000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001', null,
        'EVENT', 'Sports day', '2026-12-10', '2026-12-10') returning id;

-- TenantTx
select set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);

do $$ begin raise notice 'ALL APPLICATION QUERIES EXECUTED AGAINST THE SCHEMA'; end $$;
rollback;

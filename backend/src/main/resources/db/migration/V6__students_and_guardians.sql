-- B3 students and guardians (M03, M01).

-- Contact details on the person. A guardian's mobile is an Indian mobile in E.164 and belongs to one person
-- per school group (it becomes the parent's OTP sign-in in B4).
alter table person add column mobile_e164 text
  check (mobile_e164 is null or mobile_e164 ~ '^\+91[6-9][0-9]{9}$');
create unique index person_mobile_unique on person (tenant_id, mobile_e164) where mobile_e164 is not null;
alter table person add column email text
  check (email is null or (char_length(email) <= 200 and email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'));

-- One open relationship per guardian and child; ended ones stay as history.
create unique index guardian_rel_one_open on guardian_relationship (tenant_id, guardian_person_id, student_person_id)
  where status <> 'ENDED';
alter table guardian_relationship add column updated_at timestamptz;
alter table guardian_relationship add column updated_by uuid;
alter table guardian_relationship add column ended_at timestamptz;

create index household_member_person_idx on household_member (tenant_id, person_id);
create index student_enrollment_year_idx on student_enrollment (tenant_id, academic_year_id);

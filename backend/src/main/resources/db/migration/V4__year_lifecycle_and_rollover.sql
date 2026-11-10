-- B1 completion: academic-year lifecycle and committed rollover.

-- At most one ACTIVE year per campus (activating a year closes the previous one in the same transaction).
create unique index academic_year_one_active on academic_year (tenant_id, campus_id) where status = 'ACTIVE';

-- A year created by rollover remembers its source; a year can be rolled over only once.
alter table academic_year add column rolled_from uuid;
alter table academic_year add foreign key (tenant_id, rolled_from) references academic_year(tenant_id, id);
create unique index academic_year_rolled_from_once on academic_year (tenant_id, rolled_from) where rolled_from is not null;

-- Placement history: which enrollment a carried-forward enrollment came from.
alter table student_enrollment add unique (tenant_id, id);
alter table student_enrollment add column promoted_from uuid;
alter table student_enrollment add foreign key (tenant_id, promoted_from) references student_enrollment(tenant_id, id);

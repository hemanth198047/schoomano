-- B38 / M29: guardians in the student import. The ids of the guardian relationships and guardian records an import row
-- created are kept on the row, so a rollback removes exactly those (and only while nobody has changed them since).
alter table import_row add column guardian_ops jsonb;

#!/bin/sh
# Runs once on first container start. Creates the unprivileged application login role.
# Flyway (as the owner) creates schema objects and grants; the app role never owns tables,
# so PostgreSQL row-level security applies to every application query.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
create role schoomano_app login password '${SCHOOMANO_DB_APP_PASSWORD}' nosuperuser nocreatedb nocreaterole nobypassrls;
SQL

#!/bin/sh
# Database tests: RLS isolation, audit chain, effective dating, and the API's SQL. Rolls back; needs seed data.
set -eu
. "$(dirname -- "$0")/lib/common.sh"
load_env
require_docker
psql_in_db -q -f /schoomano-db/tests/rls_and_invariants_test.sql -f /schoomano-db/tests/academic_structure_test.sql -f /schoomano-db/tests/sign_in_test.sql -f /schoomano-db/tests/timetable_test.sql -f /schoomano-db/tests/attendance_test.sql -f /schoomano-db/tests/notices_test.sql -f /schoomano-db/tests/fees_test.sql -f /schoomano-db/tests/support_test.sql -f /schoomano-db/tests/assessment_test.sql -f /schoomano-db/tests/admissions_test.sql -f /schoomano-db/tests/learning_test.sql -f /schoomano-db/tests/student_support_test.sql -f /schoomano-db/tests/safeguarding_test.sql -f /schoomano-db/tests/health_test.sql -f /schoomano-db/tests/procurement_test.sql -f /schoomano-db/tests/hr_test.sql -f /schoomano-db/tests/transport_test.sql -f /schoomano-db/tests/cocurricular_test.sql -f /schoomano-db/tests/trips_test.sql -f /schoomano-db/tests/facilities_test.sql -f /schoomano-db/tests/compliance_test.sql -f /schoomano-db/tests/workflow_test.sql -f /schoomano-db/tests/purchase_workflow_test.sql -f /schoomano-db/tests/leave_workflow_test.sql -f /schoomano-db/tests/app_queries_smoke_test.sql

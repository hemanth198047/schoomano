<# Database tests: RLS isolation, audit chain, effective dating, and the API's SQL. Rolls back; needs seed data. #>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Import-DotEnv
Assert-Docker
Invoke-PsqlInDb @('-q', '-f', '/schoomano-db/tests/rls_and_invariants_test.sql', '-f', '/schoomano-db/tests/app_queries_smoke_test.sql')
exit 0

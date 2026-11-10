<# Load SYNTHETIC demo data into the local database. Safe to run repeatedly.
   The backend must have started once (it creates the schema with Flyway). #>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Enter-Root
try {
    Import-DotEnv
    Assert-Docker
    $ready = Get-ComposeOutput @('exec', '-T', 'postgres', 'psql', '-tA', '-U', $script:DbOwner, '-d', 'schoomano', '-c', "select to_regclass('public.tenant') is not null")
    if ("$ready".Trim() -ne 't') { Stop-WithError 'Schema not found. Start the backend first: scripts\start.cmd' }
    Write-Info 'Loading synthetic seed data'
    Invoke-PsqlInDb @('-q', '-f', '/schoomano-db/seed/dev_seed.sql')
    Invoke-PsqlInDb @('-q', '-f', '/schoomano-db/seed/dev_seed_academic.sql')
    Write-Info 'Seed complete. Sign in as Asha, Ravi, Farah, Meera, Karan or Other (all synthetic).'
    exit 0
} finally { Exit-Root }

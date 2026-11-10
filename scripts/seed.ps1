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
    # Every seed file in name order (10_base.sql first; numeric prefixes keep the order the same in every locale); each is guarded and safe to repeat.
    foreach ($f in (Get-ChildItem -Path 'backend/db/seed' -Filter '*.sql' | Sort-Object Name)) {
        Invoke-PsqlInDb @('-q', '-f', "/schoomano-db/seed/$($f.Name)")
    }
    Write-Info 'Seed complete. Sign in as Asha, Ravi, Farah, Meera, Karan or Other (all synthetic).'
    exit 0
} finally { Exit-Root }

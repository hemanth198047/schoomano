<#
Start Schoomano locally.

  scripts\start.cmd                     full stack in containers (db + backend + frontend)
  scripts\start.cmd -Dev                db in a container; backend and frontend as local processes (hot reload for web)
  scripts\start.cmd [-Dev] backend      one component (and what it needs); also: db, frontend
  options: -Seed      load synthetic demo data once the backend has migrated the database
           -NoBuild   reuse existing images / backend jar
  (Unix-style --dev, --seed, --no-build also work.)
Ports come from .env; if one is taken by another program, the next free port is used and saved to .env.
#>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Enter-Root
try {
    $Mode = 'containers'; $Seed = $false; $Build = $true; $Target = 'all'
    foreach ($a in $args) {
        if (Test-IsOption $a) {
            switch (ConvertTo-OptionName $a) {
                'dev' { $Mode = 'dev' }
                'seed' { $Seed = $true }
                { $_ -in 'nobuild', 'no-build' } { $Build = $false }
                { $_ -in 'h', 'help', '?' } { Show-Usage 'scripts/start.ps1' }
                default { Stop-WithError "Unknown option $a. Use -Help." }
            }
        } else { $Target = Resolve-Component $a }
    }

    Import-DotEnv
    Assert-Docker
    New-Item -ItemType Directory -Force -Path $script:RunDir | Out-Null
    if ($Seed -and $Target -eq 'db') { Stop-WithError '-Seed needs the backend (it creates the schema). Use: scripts\start.cmd -Seed' }
    Resolve-Ports

    $BuildArgs = @(); if ($Build) { $BuildArgs = @('--build') }
    $ApiHealth = "http://127.0.0.1:$($script:ApiPort)/actuator/health/readiness"

    if ($Mode -eq 'containers') {
        foreach ($p in 'backend', 'frontend') {
            if ((Get-RunningPid $p) -ne 0) { Stop-WithError "A local $p (-Dev) is running. Run scripts\stop.cmd first." }
        }
        switch ($Target) {
            'db' {
                Write-Info 'Starting database container'
                Invoke-Compose @('up', '-d', 'postgres'); Wait-Db
            }
            'backend' {
                Write-Info 'Starting database and backend containers'
                Invoke-Compose (@('up', '-d') + $BuildArgs + @('postgres', 'backend'))
                Wait-Http 'backend' $ApiHealth
            }
            default {
                Write-Info 'Starting database, backend and frontend containers (first build downloads dependencies and can take several minutes)'
                Invoke-Compose (@('up', '-d') + $BuildArgs)
                Wait-Http 'backend' $ApiHealth
                Wait-Http 'frontend' "http://127.0.0.1:$($script:WebPort)/healthz"
            }
        }
    } else {
        Write-Info 'Starting database container'
        Invoke-Compose @('up', '-d', 'postgres')
        Wait-Db

        if ($Target -in 'all', 'backend') {
            $existing = Get-RunningPid 'backend'
            if ($existing -ne 0) { Write-Info "Local backend already running (pid $existing)" }
            else {
                if (Test-ContainerRunning 'backend') { Stop-WithError 'The backend container is running. Run scripts\stop.cmd backend first.' }
                if (-not (Test-Command java)) { Stop-WithError 'Java 21 (JDK) is required for -Dev backend.' }
                $jar = 'backend/target/schoomano.jar'
                if ($Build -or -not (Test-Path $jar)) {
                    if (-not (Test-Command mvn)) { Stop-WithError 'Maven 3.9+ is required for -Dev backend (or use container mode: scripts\start.cmd).' }
                    Write-Info "Building backend (mvn package, tests skipped; run 'cd backend; mvn verify' for tests)"
                    Invoke-Native mvn @('-B', '-q', '-f', 'backend/pom.xml', '-DskipTests', 'package')
                    if ($script:NativeExit -ne 0) { Stop-WithError 'Backend build failed.' }
                }
                Write-Info "Starting local backend on port $($script:ApiPort)"
                Set-ScriptEnv 'SCHOOMANO_PORT' "$($script:ApiPort)"
                Set-ScriptEnv 'SCHOOMANO_DB_URL' "jdbc:postgresql://localhost:$($script:DbPort)/schoomano"
                $id = Start-Tracked 'backend' 'java' "-jar $jar"
                Wait-Http 'backend' $ApiHealth $id
            }
        }

        if ($Target -in 'all', 'frontend') {
            $existing = Get-RunningPid 'frontend'
            if ($existing -ne 0) { Write-Info "Local frontend already running (pid $existing)" }
            else {
                if (-not (Test-Command node)) { Stop-WithError 'Node.js 22+ is required for -Dev frontend.' }
                if (-not (Test-Command npm)) { Stop-WithError 'npm is required for -Dev frontend.' }
                if (-not (Test-Path 'frontend/node_modules')) {
                    Write-Info 'Installing frontend dependencies'
                    Invoke-Native npm @('--prefix', 'frontend', 'ci', '--no-audit', '--no-fund')
                    if ($script:NativeExit -ne 0) { Stop-WithError 'npm ci failed.' }
                }
                Invoke-Native npm @('--prefix', 'frontend', 'run', '-s', 'tokens') -Quiet
                if ($script:NativeExit -ne 0) { Stop-WithError 'Design token build failed.' }
                Write-Info "Starting local frontend (Vite) on port $($script:DevWebPort)"
                Set-ScriptEnv 'SCHOOMANO_BACKEND_URL' "http://127.0.0.1:$($script:ApiPort)"
                $devAuth = 'false'; if ($env:SCHOOMANO_AUTH_DEV_MODE) { $devAuth = $env:SCHOOMANO_AUTH_DEV_MODE }
                Set-ScriptEnv 'VITE_DEV_AUTH' $devAuth
                $id = Start-Tracked 'frontend' 'node' "frontend/node_modules/vite/bin/vite.js frontend --port $($script:DevWebPort) --strictPort"
                Wait-Http 'frontend' "http://127.0.0.1:$($script:DevWebPort)/" $id
            }
        }
    }

    if ($Seed) {
        & (Join-Path $PSScriptRoot 'seed.ps1')
        if ($LASTEXITCODE -ne 0) { exit 1 }
    }

    Write-Host ''
    Write-Info "Schoomano is running ($Mode mode)"
    if ($Target -in 'all', 'frontend') {
        if ($Mode -eq 'dev') { Write-Host "  Web app:  http://localhost:$($script:DevWebPort)  (hot reload)" }
        else { Write-Host "  Web app:  http://localhost:$($script:WebPort)" }
    }
    if ($Target -in 'all', 'backend') { Write-Host "  API:      http://localhost:$($script:ApiPort)/actuator/health" }
    Write-Host "  Database: localhost:$($script:DbPort) (db schoomano)"
    Write-Host '  Stop with: scripts\stop.cmd    Status: scripts\status.cmd'
    exit 0
} finally { Exit-Root }

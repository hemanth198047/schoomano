# Shared helpers for the PowerShell scripts. Works on Windows PowerShell 5.1 and PowerShell 7+.
# Dot-sourced by scripts\*.ps1; not run directly.

$ErrorActionPreference = 'Stop'
$script:Root = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
$script:RunDir = Join-Path $script:Root '.run'
$script:ComposeFile = Join-Path (Join-Path $script:Root 'infra') 'docker-compose.yml'
$script:EnvFile = Join-Path $script:Root '.env'
$script:OnWindows = ($env:OS -eq 'Windows_NT')
$script:WaitSeconds = 240
if ($env:SCHOOMANO_WAIT_SECONDS) { $script:WaitSeconds = [int]$env:SCHOOMANO_WAIT_SECONDS }

function Write-Info([string]$Message) { Write-Host "==> $Message" }
function Write-Warn([string]$Message) { Write-Host "warning: $Message" -ForegroundColor Yellow }
function Stop-WithError([string]$Message) { Write-Host "error: $Message" -ForegroundColor Red; exit 1 }

function Test-Command([string]$Name) { return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

# Runs a native command without letting stderr output become a terminating error (Windows PowerShell 5.1).
function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [switch]$Quiet)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) { & $File @Arguments *> $null } else { & $File @Arguments | Out-Host }
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}

function New-RandomSecret {
    $chars = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    return -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

# Creates .env from .env.example on first run, with random local database passwords.
function Initialize-EnvFile {
    if (Test-Path $script:EnvFile) { return }
    $example = Join-Path $script:Root '.env.example'
    if (-not (Test-Path $example)) { Stop-WithError '.env.example is missing' }
    $owner = New-RandomSecret
    $app = New-RandomSecret
    $lines = Get-Content $example | ForEach-Object {
        if ($_ -match '^SCHOOMANO_DB_OWNER_PASSWORD=') { "SCHOOMANO_DB_OWNER_PASSWORD=$owner" }
        elseif ($_ -match '^SCHOOMANO_DB_APP_PASSWORD=') { "SCHOOMANO_DB_APP_PASSWORD=$app" }
        else { $_ }
    }
    # UTF-8 without BOM so Docker Compose reads the first key correctly.
    [System.IO.File]::WriteAllLines($script:EnvFile, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Info 'Created .env with random local database passwords'
}

function Import-DotEnv {
    Initialize-EnvFile
    foreach ($line in Get-Content $script:EnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    $script:DbOwner = if ($env:SCHOOMANO_DB_OWNER_USER) { $env:SCHOOMANO_DB_OWNER_USER } else { 'schoomano_owner' }
    $script:ApiPort = if ($env:SCHOOMANO_API_PORT) { $env:SCHOOMANO_API_PORT } else { '8080' }
    $script:WebPort = if ($env:SCHOOMANO_WEB_PORT) { $env:SCHOOMANO_WEB_PORT } else { '8081' }
    $script:DevWebPort = '5173'
}

function Assert-Docker {
    if (-not (Test-Command docker)) { Stop-WithError 'Docker is not installed or not on PATH. Install Docker Desktop.' }
    if ((Invoke-Native docker @('info') -Quiet) -ne 0) {
        Stop-WithError 'Docker is installed but not running. Start Docker Desktop and retry.'
    }
}

function Test-DockerRunning {
    if (-not (Test-Command docker)) { return $false }
    return ((Invoke-Native docker @('info') -Quiet) -eq 0)
}

function Get-ComposeCommand {
    $base = @('--env-file', $script:EnvFile, '-f', $script:ComposeFile, '-p', 'schoomano')
    if ((Invoke-Native docker @('compose', 'version') -Quiet) -eq 0) { return @{ File = 'docker'; Prefix = @('compose') + $base } }
    if (Test-Command docker-compose) { return @{ File = 'docker-compose'; Prefix = $base } }
    Stop-WithError 'Docker Compose not found. Install or update Docker Desktop.'
}

# docker compose with the project's files. Output goes to the console; the exit code is kept in
# $script:ComposeExit. Stops the script on failure unless -AllowFail.
function Invoke-Compose {
    param([string[]]$Arguments, [switch]$AllowFail, [switch]$Quiet)
    $c = Get-ComposeCommand
    $script:ComposeExit = Invoke-Native $c.File ($c.Prefix + $Arguments) -Quiet:$Quiet
    if ($script:ComposeExit -ne 0 -and -not $AllowFail) {
        Stop-WithError "docker compose $($Arguments -join ' ') failed (exit $($script:ComposeExit))"
    }
}

# Same, but returns the command's standard output as text (errors suppressed).
function Get-ComposeOutput([string[]]$Arguments) {
    $c = Get-ComposeCommand
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return ((& $c.File ($c.Prefix + $Arguments) 2>$null) -join "`n") } finally { $ErrorActionPreference = $old }
}

# psql inside the database container as the schema owner (bypasses RLS: seeding and tests only).
function Invoke-PsqlInDb([string[]]$Arguments) {
    Invoke-Compose (@('exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', $script:DbOwner, '-d', 'schoomano') + $Arguments)
}

function Test-Http([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300)
    } catch { return $false }
}

function Wait-Http([string]$Name, [string]$Url, [int]$ProcessId = 0) {
    Write-Info "Waiting for $Name ($Url), up to $($script:WaitSeconds)s"
    $deadline = (Get-Date).AddSeconds($script:WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Http $Url) { Write-Info "$Name is up"; return }
        if ($ProcessId -ne 0 -and -not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            Stop-WithError "$Name exited during startup. See .run\$Name.log and .run\$Name.err.log"
        }
        Start-Sleep -Seconds 2
    }
    Stop-WithError "$Name did not become ready in $($script:WaitSeconds)s. Check: scripts\logs.cmd $Name"
}

function Wait-Db {
    Write-Info 'Waiting for PostgreSQL'
    $deadline = (Get-Date).AddSeconds($script:WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        Invoke-Compose @('exec', '-T', 'postgres', 'pg_isready', '-q', '-U', $script:DbOwner, '-d', 'schoomano') -AllowFail -Quiet
        if ($script:ComposeExit -eq 0) { Write-Info 'PostgreSQL is up'; return }
        Start-Sleep -Seconds 2
    }
    Stop-WithError 'PostgreSQL did not become ready. Check: scripts\logs.cmd db'
}

# ---- local (dev-mode) processes, tracked with pid files in .run\ ----

function Get-PidFile([string]$Name) { return (Join-Path $script:RunDir "$Name.pid") }

# Returns the pid if the tracked process is alive and is the expected program (guards against pid reuse), else 0.
function Get-RunningPid([string]$Name) {
    $file = Get-PidFile $Name
    if (-not (Test-Path $file)) { return 0 }
    $id = [int](Get-Content $file -Raw).Trim()
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    $expected = @{ backend = 'java'; frontend = 'node' }[$Name]
    if (-not $proc -or ($expected -and $proc.ProcessName -notlike "$expected*")) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
        return 0
    }
    return $id
}

function Stop-Tracked([string]$Name) {
    $id = Get-RunningPid $Name
    if ($id -eq 0) { return }
    Write-Info "Stopping local $Name (pid $id)"
    if ($script:OnWindows) {
        Invoke-Native taskkill @('/PID', "$id", '/T', '/F') -Quiet | Out-Null   # includes child processes (e.g. esbuild)
    } else {
        Stop-Process -Id $id -ErrorAction SilentlyContinue
        $i = 0
        while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and $i -lt 30) { Start-Sleep -Seconds 1; $i++ }
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item (Get-PidFile $Name) -Force -ErrorAction SilentlyContinue
}

function Start-Tracked([string]$Name, [string]$File, [string]$Arguments, [string]$WorkingDirectory) {
    $opts = @{
        FilePath               = $File
        ArgumentList           = $Arguments
        WorkingDirectory       = $WorkingDirectory
        RedirectStandardOutput = (Join-Path $script:RunDir "$Name.log")
        RedirectStandardError  = (Join-Path $script:RunDir "$Name.err.log")
        PassThru               = $true
    }
    if ($script:OnWindows) { $opts.WindowStyle = 'Hidden' }
    $proc = Start-Process @opts
    Set-Content -Path (Get-PidFile $Name) -Value $proc.Id -Encoding ascii
    return $proc.Id
}

# Prints the <# ... #> header of the calling script as usage text.
function Show-Usage([string]$ScriptPath) {
    $text = Get-Content $ScriptPath -Raw
    if ($text -match '(?s)<#(.*?)#>') { Write-Host $Matches[1].Trim() }
    exit 0
}

function Resolve-Component([string]$Value) {
    switch -Regex ($Value.ToLowerInvariant()) {
        '^(all)?$' { return 'all' }
        '^(db|database|postgres)$' { return 'db' }
        '^(backend|api)$' { return 'backend' }
        '^(frontend|web)$' { return 'frontend' }
        default { Stop-WithError "Unknown component '$Value'. Use: all, db, backend, frontend" }
    }
}

# Accepts -Dev, --dev or /dev style options, case-insensitive.
function ConvertTo-OptionName([string]$Arg) { return ($Arg -replace '^(--|-|/)', '').ToLowerInvariant() }
function Test-IsOption([string]$Arg) { return ($Arg -match '^(--|-|/)[A-Za-z]') }

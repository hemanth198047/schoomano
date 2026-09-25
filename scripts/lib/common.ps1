# Shared helpers for the PowerShell scripts. Works on Windows PowerShell 5.1 and PowerShell 7+.
# Dot-sourced by scripts\*.ps1; not run directly.
#
# Every script calls Enter-Root first: it switches to the repository root so all paths below are
# relative, and Exit-Root (in a finally block) restores your folder and any environment variables the
# script changed, so nothing leaks into your PowerShell session.

$ErrorActionPreference = 'Stop'
$script:RunDir = '.run'
$script:ComposeFile = 'infra/docker-compose.yml'
$script:EnvFile = '.env'
$script:OnWindows = ($env:OS -eq 'Windows_NT')
$script:WaitSeconds = 240
if ($env:SCHOOMANO_WAIT_SECONDS) { $script:WaitSeconds = [int]$env:SCHOOMANO_WAIT_SECONDS }
$script:SavedEnv = @{}
$script:NativeExit = 0

function Set-ScriptEnv([string]$Name, [string]$Value) {
    if (-not $script:SavedEnv.ContainsKey($Name)) {
        $script:SavedEnv[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Enter-Root {
    Push-Location (Join-Path (Join-Path $PSScriptRoot '..') '..')
    # Plain progress output: Docker Compose/BuildKit then need no interactive console. Without this,
    # Compose on Windows can fail with "failed to get console: The handle is invalid".
    Set-ScriptEnv 'COMPOSE_PROGRESS' 'plain'
    Set-ScriptEnv 'BUILDKIT_PROGRESS' 'plain'
}

function Exit-Root {
    foreach ($name in $script:SavedEnv.Keys) {
        [Environment]::SetEnvironmentVariable($name, $script:SavedEnv[$name], 'Process')
    }
    Pop-Location
}

function Write-Info([string]$Message) { Write-Host "==> $Message" }
function Write-Warn([string]$Message) { Write-Host "warning: $Message" -ForegroundColor Yellow }
function Stop-WithError([string]$Message) { Write-Host "error: $Message" -ForegroundColor Red; exit 1 }

function Test-Command([string]$Name) { return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

# Runs a native program with its output going straight to the console (never through a pipe, which
# breaks programs that need a real console). The exit code is left in $script:NativeExit.
function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [switch]$Quiet)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) { & $File @Arguments *> $null } else { & $File @Arguments }
        $script:NativeExit = $LASTEXITCODE
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

function Write-EnvFile([string[]]$Lines) {
    # UTF-8 without BOM so Docker Compose reads the first key correctly.
    $full = Join-Path (Get-Location).Path $script:EnvFile
    [System.IO.File]::WriteAllLines($full, $Lines, (New-Object System.Text.UTF8Encoding($false)))
}

# Creates .env from .env.example on first run, with random local database passwords.
function Initialize-EnvFile {
    if (Test-Path $script:EnvFile) { return }
    if (-not (Test-Path '.env.example')) { Stop-WithError '.env.example is missing' }
    $owner = New-RandomSecret
    $app = New-RandomSecret
    $lines = Get-Content '.env.example' | ForEach-Object {
        if ($_ -match '^SCHOOMANO_DB_OWNER_PASSWORD=') { "SCHOOMANO_DB_OWNER_PASSWORD=$owner" }
        elseif ($_ -match '^SCHOOMANO_DB_APP_PASSWORD=') { "SCHOOMANO_DB_APP_PASSWORD=$app" }
        else { $_ }
    }
    Write-EnvFile ([string[]]$lines)
    Write-Info 'Created .env with random local database passwords'
}

# Updates (or appends) one KEY=VALUE line in .env.
function Set-EnvValue([string]$Key, [string]$Value) {
    $lines = [System.Collections.Generic.List[string]]::new()
    $found = $false
    foreach ($line in (Get-Content $script:EnvFile)) {
        if ($line -match "^$Key=") { $lines.Add("$Key=$Value"); $found = $true } else { $lines.Add($line) }
    }
    if (-not $found) { $lines.Add("$Key=$Value") }
    Write-EnvFile $lines.ToArray()
}

function Update-Ports {
    $defaults = [ordered]@{ SCHOOMANO_DB_PORT = '5432'; SCHOOMANO_API_PORT = '8080'; SCHOOMANO_WEB_PORT = '8081'; SCHOOMANO_DEV_WEB_PORT = '5173' }
    foreach ($k in $defaults.Keys) {
        $v = [Environment]::GetEnvironmentVariable($k, 'Process')
        if (-not $v) { $v = $defaults[$k]; Set-ScriptEnv $k $v }
        if ($v -notmatch '^\d+$') { Stop-WithError "Ports in .env must be numbers ($k='$v')." }
    }
    $script:DbPort = [int]$env:SCHOOMANO_DB_PORT
    $script:ApiPort = [int]$env:SCHOOMANO_API_PORT
    $script:WebPort = [int]$env:SCHOOMANO_WEB_PORT
    $script:DevWebPort = [int]$env:SCHOOMANO_DEV_WEB_PORT
}

function Import-DotEnv {
    Initialize-EnvFile
    foreach ($line in Get-Content $script:EnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') { Set-ScriptEnv $Matches[1] $Matches[2] }
    }
    $script:DbOwner = if ($env:SCHOOMANO_DB_OWNER_USER) { $env:SCHOOMANO_DB_OWNER_USER } else { 'schoomano_owner' }
    Update-Ports
}

function Test-DockerRunning {
    if (-not (Test-Command docker)) { return $false }
    Invoke-Native docker @('info') -Quiet
    return ($script:NativeExit -eq 0)
}

function Assert-Docker {
    if (-not (Test-Command docker)) { Stop-WithError 'Docker is not installed or not on PATH. Install Docker Desktop.' }
    if (-not (Test-DockerRunning)) { Stop-WithError 'Docker is installed but not running. Start Docker Desktop and retry.' }
}

function Get-ComposeCommand {
    $base = @('--env-file', $script:EnvFile, '-f', $script:ComposeFile, '-p', 'schoomano')
    Invoke-Native docker @('compose', 'version') -Quiet
    if ($script:NativeExit -eq 0) { return @{ File = 'docker'; Prefix = @('compose') + $base } }
    if (Test-Command docker-compose) { return @{ File = 'docker-compose'; Prefix = $base } }
    Stop-WithError 'Docker Compose not found. Install or update Docker Desktop.'
}

# docker compose with the project's files; output goes straight to the console. Stops the script on
# failure unless -AllowFail; the exit code is left in $script:ComposeExit.
function Invoke-Compose {
    param([string[]]$Arguments, [switch]$AllowFail, [switch]$Quiet)
    $c = Get-ComposeCommand
    Invoke-Native $c.File ($c.Prefix + $Arguments) -Quiet:$Quiet
    $script:ComposeExit = $script:NativeExit
    if ($script:ComposeExit -ne 0 -and -not $AllowFail) {
        Stop-WithError "docker compose $($Arguments -join ' ') failed (exit $($script:ComposeExit)). The lines above show Docker's reason."
    }
}

# Returns a compose command's standard output as text (for short, non-interactive queries only).
function Get-ComposeOutput([string[]]$Arguments) {
    $c = Get-ComposeCommand
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return ((& $c.File ($c.Prefix + $Arguments) 2>$null) -join "`n") } finally { $ErrorActionPreference = $old }
}

function Test-ContainerRunning([string]$Service) { return [bool](Get-ComposeOutput @('ps', '-q', $Service)).Trim() }

# psql inside the database container as the schema owner (bypasses RLS: seeding and tests only).
function Invoke-PsqlInDb([string[]]$Arguments) {
    Invoke-Compose (@('exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', $script:DbOwner, '-d', 'schoomano') + $Arguments)
}

# ---- port availability ----

# True if the port cannot be used on 127.0.0.1: something accepts connections there, or Windows refuses
# the bind (e.g. a port inside a Hyper-V/WSL excluded range).
function Test-PortInUse([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if ($attempt.AsyncWaitHandle.WaitOne(300) -and $client.Connected) { return $true }
    } catch { } finally { $client.Close() }
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $false } catch { return $true } finally { try { $listener.Stop() } catch { } }
}

# Keeps a port that is free or already held by Schoomano itself; otherwise picks the next free one
# and saves it in .env.
function Select-Port([string]$Key, [string]$Label, [bool]$OwnedBySchoomano, [System.Collections.Generic.List[int]]$Reserved) {
    $current = [int][Environment]::GetEnvironmentVariable($Key, 'Process')
    if ($OwnedBySchoomano) { return }
    $candidate = $current
    $tries = 0
    while ((Test-PortInUse $candidate) -or ($candidate -ne $current -and $Reserved.Contains($candidate))) {
        $candidate++; $tries++
        if ($tries -gt 100) { Stop-WithError "No free port found near $current for the $Label." }
    }
    if ($candidate -ne $current) {
        Write-Warn "Port $current (for the $Label) is in use by another program; using $candidate instead (saved in .env)."
        Set-EnvValue $Key "$candidate"
        Set-ScriptEnv $Key "$candidate"
        $Reserved.Add($candidate)
    }
}

function Resolve-Ports {
    $reserved = [System.Collections.Generic.List[int]]::new()
    foreach ($p in @($script:DbPort, $script:ApiPort, $script:WebPort, $script:DevWebPort)) { $reserved.Add($p) }
    Select-Port 'SCHOOMANO_DB_PORT' 'database' (Test-ContainerRunning 'postgres') $reserved
    Select-Port 'SCHOOMANO_API_PORT' 'API' ((Test-ContainerRunning 'backend') -or ((Get-RunningPid 'backend') -ne 0)) $reserved
    Select-Port 'SCHOOMANO_WEB_PORT' 'web app' (Test-ContainerRunning 'frontend') $reserved
    Select-Port 'SCHOOMANO_DEV_WEB_PORT' 'dev web server' ((Get-RunningPid 'frontend') -ne 0) $reserved
    Update-Ports
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
            Stop-WithError "$Name exited during startup. See $($script:RunDir)/$Name.log and $($script:RunDir)/$Name.err.log"
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

function Get-PidFile([string]$Name) { return "$($script:RunDir)/$Name.pid" }

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
        Invoke-Native taskkill @('/PID', "$id", '/T', '/F') -Quiet   # includes child processes (e.g. esbuild)
    } else {
        Stop-Process -Id $id -ErrorAction SilentlyContinue
        $i = 0
        while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and $i -lt 30) { Start-Sleep -Seconds 1; $i++ }
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item (Get-PidFile $Name) -Force -ErrorAction SilentlyContinue
}

# Starts a background process from the repository root; logs go to .run\<name>.log and .run\<name>.err.log.
function Start-Tracked([string]$Name, [string]$File, [string]$Arguments) {
    $opts = @{
        FilePath               = $File
        ArgumentList           = $Arguments
        WorkingDirectory       = (Get-Location).Path
        RedirectStandardOutput = "$($script:RunDir)/$Name.log"
        RedirectStandardError  = "$($script:RunDir)/$Name.err.log"
        PassThru               = $true
    }
    if ($script:OnWindows) { $opts.WindowStyle = 'Hidden' }
    $proc = Start-Process @opts
    Set-Content -Path (Get-PidFile $Name) -Value $proc.Id -Encoding ascii
    return $proc.Id
}

# Prints the <# ... #> header of a script as usage text.
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

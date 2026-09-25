<#
Stop Schoomano.

  scripts\stop.cmd                  stop everything (local -Dev processes and containers); data is kept
  scripts\stop.cmd backend          stop one component; also: db, frontend
  scripts\stop.cmd -Purge           also DELETE the local database volume (asks first; -Yes skips the question)
#>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Enter-Root
try {
    $Target = 'all'; $Purge = $false; $Yes = $false
    foreach ($a in $args) {
        if (Test-IsOption $a) {
            switch (ConvertTo-OptionName $a) {
                'purge' { $Purge = $true }
                { $_ -in 'yes', 'y' } { $Yes = $true }
                { $_ -in 'h', 'help', '?' } { Show-Usage 'scripts/stop.ps1' }
                default { Stop-WithError "Unknown option $a. Use -Help." }
            }
        } else { $Target = Resolve-Component $a }
    }
    if ($Purge -and $Target -ne 'all') { Stop-WithError '-Purge applies to the whole stack only' }

    Import-DotEnv

    switch ($Target) {
        'all' { Stop-Tracked 'frontend'; Stop-Tracked 'backend' }
        'backend' { Stop-Tracked 'backend' }
        'frontend' { Stop-Tracked 'frontend' }
    }

    if (-not (Test-DockerRunning)) { Write-Warn 'Docker is not running; skipped containers.'; exit 0 }

    switch ($Target) {
        'all' {
            if ($Purge) {
                if (-not $Yes) {
                    $answer = Read-Host 'This deletes the LOCAL Schoomano database volume. Type "delete" to confirm'
                    if ($answer -ne 'delete') { Stop-WithError 'Cancelled; nothing deleted.' }
                }
                Write-Info 'Removing containers and the local database volume'
                Invoke-Compose @('down', '-v', '--remove-orphans')
            } else {
                Write-Info 'Stopping containers (database volume kept)'
                Invoke-Compose @('down', '--remove-orphans')
            }
        }
        'db' { Write-Info 'Stopping database container'; Invoke-Compose @('stop', 'postgres') }
        'backend' { Write-Info 'Stopping backend container'; Invoke-Compose @('stop', 'backend') }
        'frontend' { Write-Info 'Stopping frontend container'; Invoke-Compose @('stop', 'frontend') }
    }
    Write-Info 'Stopped'
    exit 0
} finally { Exit-Root }

<# Show what is running: local -Dev processes, containers, and health endpoints. #>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Import-DotEnv

Write-Info 'Local processes (-Dev)'
foreach ($p in 'backend', 'frontend') {
    $id = Get-RunningPid $p
    if ($id -ne 0) { Write-Host "  ${p}: running (pid $id, log .run\$p.log)" } else { Write-Host "  ${p}: not running" }
}

Write-Info 'Containers'
if (Test-DockerRunning) { Invoke-Compose @('ps') -AllowFail } else { Write-Host '  Docker is not running' }

Write-Info 'Health'
function Show-Health([string]$Name, [string]$Url) {
    if (Test-Http $Url) { Write-Host "  ${Name}: OK   $Url" } else { Write-Host "  ${Name}: --   $Url" }
}
Show-Health 'API' "http://127.0.0.1:$($script:ApiPort)/actuator/health/readiness"
Show-Health 'Web (containers)' "http://127.0.0.1:$($script:WebPort)/healthz"
Show-Health 'Web (-Dev)' "http://127.0.0.1:$($script:DevWebPort)/"
exit 0

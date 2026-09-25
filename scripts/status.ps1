<# Show what is running: local -Dev processes, containers, and health endpoints. #>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Enter-Root
try {
    Import-DotEnv

    Write-Info 'Local processes (-Dev)'
    foreach ($p in 'backend', 'frontend') {
        $id = Get-RunningPid $p
        if ($id -ne 0) { Write-Host "  ${p}: running (pid $id, log $($script:RunDir)/$p.log)" } else { Write-Host "  ${p}: not running" }
    }

    Write-Info 'Containers'
    if (Test-DockerRunning) { Invoke-Compose @('ps') -AllowFail } else { Write-Host '  Docker is not running' }

    Write-Info 'Health'
    foreach ($check in @(
            @('API', "http://127.0.0.1:$($script:ApiPort)/actuator/health/readiness"),
            @('Web (containers)', "http://127.0.0.1:$($script:WebPort)/healthz"),
            @('Web (-Dev)', "http://127.0.0.1:$($script:DevWebPort)/"))) {
        if (Test-Http $check[1]) { Write-Host "  $($check[0]): OK   $($check[1])" } else { Write-Host "  $($check[0]): --   $($check[1])" }
    }
    exit 0
} finally { Exit-Root }

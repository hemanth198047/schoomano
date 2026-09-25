<# Show logs:  scripts\logs.cmd [backend|frontend|db] [-Follow]
   Uses the local -Dev log if that component runs locally, otherwise the container log. #>
. (Join-Path (Join-Path $PSScriptRoot 'lib') 'common.ps1')
Enter-Root
try {
    Import-DotEnv
    $Target = 'all'; $Follow = $false
    foreach ($a in $args) {
        if ((Test-IsOption $a) -and ((ConvertTo-OptionName $a) -in 'f', 'follow')) { $Follow = $true }
        else { $Target = Resolve-Component $a }
    }
    if ($Target -in 'backend', 'frontend' -and (Get-RunningPid $Target) -ne 0) {
        $log = "$($script:RunDir)/$Target.log"
        if ($Follow) { Get-Content $log -Tail 200 -Wait } else { Get-Content $log -Tail 200 }
        exit 0
    }
    $svc = @(); if ($Target -eq 'db') { $svc = @('postgres') } elseif ($Target -ne 'all') { $svc = @($Target) }
    $argsList = @('logs', '--tail=200'); if ($Follow) { $argsList += '-f' }
    Invoke-Compose ($argsList + $svc) -AllowFail
    exit 0
} finally { Exit-Root }

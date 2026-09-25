# Usage (from repo root):  . .\scripts\load-env.ps1
# Loads .env into the current PowerShell session so Maven and npm see the variables.
Get-Content (Join-Path $PSScriptRoot "..\.env") | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}
Write-Host "Loaded .env into this session"

@echo off
rem Runs seed.ps1 without changing the PowerShell execution policy. Works in cmd.exe and PowerShell.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0seed.ps1" %*
exit /b %ERRORLEVEL%

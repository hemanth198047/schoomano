@echo off
rem Runs start.ps1 without changing the PowerShell execution policy. Works in cmd.exe and PowerShell.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
exit /b %ERRORLEVEL%

@echo off
rem Runs stop.ps1 without changing the PowerShell execution policy. Works in cmd.exe and PowerShell.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
exit /b %ERRORLEVEL%

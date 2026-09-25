@echo off
rem Runs db-test.ps1 without changing the PowerShell execution policy. Works in cmd.exe and PowerShell.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0db-test.ps1" %*
exit /b %ERRORLEVEL%

@echo off
rem Runs logs.ps1 without changing the PowerShell execution policy. Works in cmd.exe and PowerShell.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0logs.ps1" %*
exit /b %ERRORLEVEL%

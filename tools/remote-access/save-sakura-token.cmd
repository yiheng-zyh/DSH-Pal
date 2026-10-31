@echo off
powershell.exe -NoProfile -STA -File "%~dp0secure-store.ps1"
if errorlevel 1 pause

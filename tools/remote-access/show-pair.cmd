@echo off
cd /d "%~dp0"
node show-pair.mjs
if errorlevel 1 pause

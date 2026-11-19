@echo off
set "PATH=%~dp0..\.labflow-tools\node-v24.11.1-win-x64;%PATH%"
cd /d "%~dp0"
call npm.cmd run dev
pause

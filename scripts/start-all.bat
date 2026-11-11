@echo off
rem start-all.bat - wrapper that runs start-all.ps1 with -ExecutionPolicy Bypass
rem (PowerShell 5.1 blocks unsigned .ps1, so this wrapper is the supported entry)
rem usage: double-click, or run "start-all.bat [args]" from this folder
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
set RC=%ERRORLEVEL%
echo.
if %RC% neq 0 (
  echo [FAIL] start-all exited with code %RC%
  echo        see _run\start-all.log in the repo root ^(also java.log / py.log / web.log^)
) else (
  echo [OK] services are running in background ^(closing this window is safe^):
  echo      Web  http://localhost:8081
  echo      API  http://localhost:8080
  echo      RAG  http://127.0.0.1:8000
  echo.
  echo      stop all:  stop-all.bat
)
echo.
pause
endlocal & exit /b %RC%